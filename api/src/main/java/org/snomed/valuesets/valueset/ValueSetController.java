package org.snomed.valuesets.valueset;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** Access rules live in {@link ValueSetService}; this class only maps HTTP to it. */
@Validated
@RestController
@RequestMapping("/api")
public class ValueSetController {

	private static final int MAX_PAGE_SIZE = 100;

	private final ValueSetService service;
	private final SnowstormClient snowstorm;

	public ValueSetController(ValueSetService service, SnowstormClient snowstorm) {
		this.service = service;
		this.snowstorm = snowstorm;
	}

	@GetMapping("/value-sets")
	public List<ValueSetView> list(CurrentUser user) {
		return service.list(user);
	}

	@GetMapping("/tags")
	public List<String> tags(CurrentUser user) {
		return service.tags(user);
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

	/** The latest published version of a shared value set, in the Global Patient Set file layout. */
	@GetMapping("/value-sets/{id}/download")
	public ResponseEntity<String> download(@PathVariable long id, CurrentUser user) {
		GpsFile file = service.download(id, user);
		return ResponseEntity.ok()
				.contentType(new MediaType("text", "tab-separated-values", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(file.fileName()).build().toString())
				.body(file.content());
	}

	@GetMapping("/value-sets/{id}/versions")
	public List<VersionView> versions(@PathVariable long id, CurrentUser user) {
		return service.versions(id, user);
	}

	@PostMapping("/value-sets/{id}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	public VersionView publish(@PathVariable long id, @Valid @RequestBody PublishRequest request,
			CurrentUser user) {
		return service.publish(id, request.note().trim(), user);
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
	public record PublishRequest(@NotNull @Size(max = 500) String note) {
	}
}
