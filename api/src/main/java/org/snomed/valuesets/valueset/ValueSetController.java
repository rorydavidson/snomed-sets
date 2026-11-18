package org.snomed.valuesets.valueset;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetView.VersionView;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Access rules live in {@link ValueSetService}; this class only maps HTTP to it. */
@Validated
@RestController
@RequestMapping("/api")
public class ValueSetController {

	private static final int MAX_PAGE_SIZE = 100;
	/** A version number, or "draft" for the owner's working draft. */
	private static final String VERSION_PARAM = "draft|[1-9]\\d{0,5}";

	private final ValueSetService service;
	private final SnowstormClient snowstorm;
	private final SetIdentifierService identifiers;

	public ValueSetController(ValueSetService service, SnowstormClient snowstorm, SetIdentifierService identifiers) {
		this.service = service;
		this.snowstorm = snowstorm;
		this.identifiers = identifiers;
	}

	@GetMapping("/value-sets")
	public List<ValueSetView> list(CurrentUser user) {
		return service.list(user);
	}

	@GetMapping("/tags")
	public List<String> tags(CurrentUser user) {
		return service.tags(user);
	}

	/** The value set a SNOMED CT reference set concept identifies, as used in share links. */
	@GetMapping("/value-sets/sct/{sctid:\\d{6,18}}")
	public ValueSetView getByIdentifier(@PathVariable String sctid, CurrentUser user) {
		return service.getByIdentifier(sctid, user);
	}

	/**
	 * Checks an identifier before it is saved, and returns the concept so the editor can show it.
	 *
	 * @param branch the release the value set is, or will be, built on
	 * @param valueSetId the set being edited, which may keep its own identifier
	 */
	@GetMapping("/set-identifiers/check")
	public SetIdentifier checkIdentifier(@RequestParam String sctid, @RequestParam String branch,
			@RequestParam(required = false) Long valueSetId, CurrentUser user) {
		return identifiers.check(sctid, branch, valueSetId, user);
	}

	@GetMapping("/value-sets/{id}")
	public ValueSetView get(@PathVariable long id, CurrentUser user) {
		return service.get(id, user);
	}

	@PostMapping("/value-sets")
	@ResponseStatus(HttpStatus.CREATED)
	public ValueSetView create(@Valid @RequestBody ValueSetDefinition definition, CurrentUser user) {
		return service.create(definition, user);
	}

	@PutMapping("/value-sets/{id}")
	public ValueSetView update(@PathVariable long id, @Valid @RequestBody ValueSetDefinition definition,
			CurrentUser user) {
		return service.update(id, definition, user);
	}

	@DeleteMapping("/value-sets/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id, CurrentUser user) {
		service.delete(id, user);
	}

	@PostMapping("/value-sets/{id}/discard-changes")
	public ValueSetView discardChanges(@PathVariable long id, CurrentUser user) {
		return service.discardChanges(id, user);
	}

	@PutMapping("/value-sets/{id}/deprecation")
	public ValueSetView deprecate(@PathVariable long id, @Valid @RequestBody DeprecationRequest request,
			CurrentUser user) {
		return service.deprecate(id, request.reason().trim(), user);
	}

	@DeleteMapping("/value-sets/{id}/deprecation")
	public ValueSetView restore(@PathVariable long id, CurrentUser user) {
		return service.restore(id, user);
	}

	@PutMapping("/value-sets/{id}/versions/{number}/deprecation")
	public VersionView deprecateVersion(@PathVariable long id, @PathVariable int number,
			@Valid @RequestBody DeprecationRequest request, CurrentUser user) {
		return service.deprecateVersion(id, number, request.reason().trim(), user);
	}

	/** Names a version, such as with its date, or with an empty label goes back to its number. Owner only. */
	@PutMapping("/value-sets/{id}/versions/{number}/label")
	public VersionView relabelVersion(@PathVariable long id, @PathVariable int number,
			@Valid @RequestBody LabelRequest request, CurrentUser user) {
		return service.relabelVersion(id, number, request.label(), user);
	}

	public record LabelRequest(@Size(max = 60) String label) {
	}

	@DeleteMapping("/value-sets/{id}/versions/{number}/deprecation")
	public VersionView restoreVersion(@PathVariable long id, @PathVariable int number, CurrentUser user) {
		return service.restoreVersion(id, number, user);
	}

	/** The hand-picked list as it is being edited, for the editor. Owner only. */
	@GetMapping("/value-sets/{id}/members")
	public List<ExpansionPage.Concept> members(@PathVariable long id, CurrentUser user) {
		return service.workingMembers(id, user);
	}

	@GetMapping("/value-sets/{id}/expansion")
	public ExpansionPage expansion(@PathVariable long id,
			@RequestParam(defaultValue = "0") @Min(0) int offset,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int limit,
			CurrentUser user) {
		return service.expansion(id, user, offset, limit);
	}

	/** A published version, or with version=draft the owner's working draft, in the Global Patient Set file layout. */
	@GetMapping("/value-sets/{id}/download")
	public ResponseEntity<String> download(@PathVariable long id,
			@RequestParam(required = false) @Pattern(regexp = VERSION_PARAM) String version, CurrentUser user) {
		GpsFile file = service.download(id, version, user);
		return ResponseEntity.ok()
				.contentType(new MediaType("text", "tab-separated-values", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.fileName()).build().toString())
				.body(file.content());
	}

	/** The same version as a FHIR R4 ValueSet resource. */
	@GetMapping(value = "/value-sets/{id}/download", params = "format=fhir")
	public ResponseEntity<Map<String, Object>> downloadFhir(@PathVariable long id,
			@RequestParam(required = false) @Pattern(regexp = VERSION_PARAM) String version, CurrentUser user) {
		// The proxy's forwarded headers are honoured, so this is the address people use.
		String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
		FhirValueSet file = service.downloadFhir(id, version, user, baseUrl);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(FhirValueSet.MEDIA_TYPE))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.fileName()).build().toString())
				.body(file.resource());
	}

	@GetMapping("/value-sets/{id}/versions")
	public List<VersionView> versions(@PathVariable long id, CurrentUser user) {
		return service.versions(id, user);
	}

	@PostMapping("/value-sets/{id}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	public VersionView publish(@PathVariable long id, @Valid @RequestBody PublishRequest request,
			CurrentUser user) {
		return service.publish(id, request.note().trim(), request.label(), user);
	}

	@GetMapping("/value-sets/{id}/versions/{number}/expansion")
	public ExpansionPage versionExpansion(@PathVariable long id, @PathVariable int number,
			@RequestParam(defaultValue = "0") @Min(0) int offset,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int limit,
			CurrentUser user) {
		return service.versionExpansion(id, number, user, offset, limit);
	}

	/** Expands an ECL expression that has not been saved, for the live preview in the editor. */
	@GetMapping("/ecl/expansion")
	public ExpansionPage preview(@RequestParam @NotBlank @Size(max = 4000) String ecl,
			@RequestParam(defaultValue = "MAIN") String branch,
			@RequestParam(defaultValue = "0") @Min(0) int offset,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int limit) {
		return snowstorm.expand(ecl, branch, offset, limit);
	}

	/** @param reason why it should no longer be used; shown to everyone who can see the value set */
	public record DeprecationRequest(@NotBlank @Size(max = 500) String reason) {
	}

	/** @param note what changed in this version; may be empty */
	/**
	 * @param label a name for the version, shown instead of its number. Left out for the default,
	 *        the date of the release the set is built on; empty for no name.
	 */
	public record PublishRequest(@NotNull @Size(max = 500) String note, @Size(max = 60) String label) {
	}
}
