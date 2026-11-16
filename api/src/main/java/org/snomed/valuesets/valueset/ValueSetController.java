package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	public record ValueSetView(long id, String name, String description, String ecl, String branch,
			ValueSet.Status status, ValueSet.Visibility visibility, String ownerName, boolean ownedByMe,
			Instant updatedAt) {

		static ValueSetView of(ValueSet valueSet, OidcUser user) {
			return new ValueSetView(valueSet.getId(), valueSet.getName(), valueSet.getDescription(),
					valueSet.getEcl(), valueSet.getBranch(), valueSet.getStatus(), valueSet.getVisibility(),
					valueSet.getOwnerName(), valueSet.getOwnerSubject().equals(user.getSubject()),
					valueSet.getUpdatedAt());
		}
	}
}
