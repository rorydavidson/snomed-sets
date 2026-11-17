package org.snomed.valuesets.valueset;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the owner confirms after looking at the preview.
 *
 * @param targetVersion the release the preview was for, so a release that came out since is not applied unseen
 * @param acceptedReplacements the suggested replacements to put in a hand-picked list; any left out are declined
 * @param keptInactiveConceptIds inactive concepts to keep in a hand-picked list, flagged as inactive;
 *        any left out are removed, which is the default
 * @param publish true to publish a new version straight away, false to keep the upgrade as a draft
 * @param note what changed, for the published version; ignored for a draft
 */
public record UpgradeRequest(@NotBlank @Pattern(regexp = ValueSetDefinition.RELEASE_REGEX) String targetVersion,
		@NotNull @Size(max = ValueSetDefinition.MAX_MEMBERS) List<@Valid @NotNull Replacement> acceptedReplacements,
		@Size(max = ValueSetDefinition.MAX_MEMBERS)
		List<@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String> keptInactiveConceptIds,
		boolean publish,
		@Size(max = 500) String note) {

	public UpgradeRequest {
		note = note == null ? "" : note.trim();
		keptInactiveConceptIds = keptInactiveConceptIds == null ? List.of() : keptInactiveConceptIds;
	}

	public record Replacement(@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String inactiveConceptId,
			@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String replacementConceptId) {
	}
}
