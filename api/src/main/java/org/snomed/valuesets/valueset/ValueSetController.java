package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.DisplayName;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/api")
public class ValueSetController {

	private static final int MAX_PAGE_SIZE = 100;

	private final ValueSetRepository valueSets;
	private final SnowstormClient snowstorm;

	public ValueSetController(ValueSetRepository valueSets, SnowstormClient snowstorm) {
		this.valueSets = valueSets;
		this.snowstorm = snowstorm;
	}

	@GetMapping("/value-sets")
	public List<ValueSetView> list(@AuthenticationPrincipal OidcUser user) {
		return valueSets.findAllVisibleTo(user.getSubject()).stream()
				.map(valueSet -> ValueSetView.of(valueSet, user))
				.toList();
	}

	@GetMapping("/value-sets/{id}")
	public ValueSetView get(@PathVariable long id, @AuthenticationPrincipal OidcUser user) {
		return ValueSetView.of(findVisible(id, user), user);
	}

	@PostMapping("/value-sets")
	@ResponseStatus(HttpStatus.CREATED)
	public ValueSetView create(@Valid @RequestBody ValueSetRequest request, @AuthenticationPrincipal OidcUser user) {
		requireValidBranch(request.branch());
		ValueSet valueSet = new ValueSet(request.name(), request.description(), request.ecl(), request.branch(),
				request.visibility(), user.getSubject(), DisplayName.of(user));
		return ValueSetView.of(valueSets.save(valueSet), user);
	}

	@PutMapping("/value-sets/{id}")
	public ValueSetView update(@PathVariable long id, @Valid @RequestBody ValueSetRequest request,
			@AuthenticationPrincipal OidcUser user) {
		requireValidBranch(request.branch());
		ValueSet valueSet = findOwned(id, user);
		valueSet.update(request.name(), request.description(), request.ecl(), request.branch(),
				request.visibility());
		return ValueSetView.of(valueSets.save(valueSet), user);
	}

	@DeleteMapping("/value-sets/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id, @AuthenticationPrincipal OidcUser user) {
		valueSets.delete(findOwned(id, user));
	}

	@GetMapping("/value-sets/{id}/expansion")
	public ExpansionPage expansion(@PathVariable long id,
			@RequestParam(defaultValue = "0") @Min(0) int offset,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int limit,
			@AuthenticationPrincipal OidcUser user) {
		ValueSet valueSet = findVisible(id, user);
		return snowstorm.expand(valueSet.getEcl(), valueSet.getBranch(), offset, limit);
	}

	/** Expands an ECL expression that has not been saved, for the live preview in the editor. */
	@GetMapping("/ecl/expansion")
	public ExpansionPage preview(@RequestParam @NotBlank @Size(max = 4000) String ecl,
			@RequestParam(defaultValue = "MAIN") String branch,
			@RequestParam(defaultValue = "0") @Min(0) int offset,
			@RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int limit) {
		return snowstorm.expand(ecl, branch, offset, limit);
	}

	private ValueSet findVisible(long id, OidcUser user) {
		// A set the caller may not see is reported as missing, so its existence is not revealed.
		return valueSets.findVisibleById(id, user.getSubject())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Value set not found"));
	}

	/** Only the owner may change or delete a set, even one that everybody can see. */
	private ValueSet findOwned(long id, OidcUser user) {
		ValueSet valueSet = findVisible(id, user);
		if (!valueSet.isOwnedBy(user.getSubject())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can change this value set.");
		}
		return valueSet;
	}

	private static void requireValidBranch(String branch) {
		if (!SnowstormClient.isValidBranch(branch)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch path is not valid.");
		}
	}

	/**
	 * The ECL is not checked against Snowstorm on save, so a draft can be kept while
	 * it is still being worked on, or while Snowstorm is unavailable.
	 */
	public record ValueSetRequest(@NotBlank @Size(max = 200) String name,
			@NotNull @Size(max = 2000) String description,
			@NotBlank @Size(max = 4000) String ecl,
			@NotBlank @Size(max = 200) String branch,
			@NotNull ValueSet.Visibility visibility) {
	}

	public record ValueSetView(long id, String name, String description, String ecl, String branch,
			ValueSet.Status status, ValueSet.Visibility visibility, String ownerName, boolean ownedByMe,
			Instant updatedAt) {

		static ValueSetView of(ValueSet valueSet, OidcUser user) {
			return new ValueSetView(valueSet.getId(), valueSet.getName(), valueSet.getDescription(),
					valueSet.getEcl(), valueSet.getBranch(), valueSet.getStatus(), valueSet.getVisibility(),
					valueSet.getOwnerName(), valueSet.isOwnedBy(user.getSubject()),
					valueSet.getUpdatedAt());
		}
	}
}
