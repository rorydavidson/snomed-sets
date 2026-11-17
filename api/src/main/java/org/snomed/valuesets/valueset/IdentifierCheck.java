package org.snomed.valuesets.valueset;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.valueset.UpgradePreview.Replacement;

/**
 * What a list of identifiers someone wants to import holds on a release branch, worked out
 * without saving anything.
 *
 * @param entryCount how many identifiers were sent, duplicates included
 * @param duplicateCount how many of them named a concept already named earlier in the list
 * @param found active concepts, in the order they were listed
 * @param inactive inactive concepts, each with the replacements the historical associations suggest
 * @param rejected identifiers that do not name a concept in this release
 */
public record IdentifierCheck(String branch, int entryCount, int duplicateCount, List<Found> found,
		List<Inactive> inactive, List<Rejected> rejected) {

	/** The most identifiers checked in one go; a list can only hold {@link ValueSetDefinition#MAX_MEMBERS}. */
	public static final int MAX_ENTRIES = 5000;

	/**
	 * @param entries each identifier, with the term the list gave it if it had a column of terms
	 */
	public record Request(@NotBlank @Pattern(regexp = SnowstormClient.BRANCH_PATH_REGEX) String branch,
			@NotNull @Size(min = 1, max = MAX_ENTRIES) List<@NotNull @Valid Entry> entries) {
	}

	/** Every concept an ECL expression matches, to add to a hand-picked list as it stands today. */
	public record EclRequest(@NotBlank @Pattern(regexp = SnowstormClient.BRANCH_PATH_REGEX) String branch,
			@NotBlank @Size(max = 4000) String ecl) {
	}

	public record Entry(@NotNull @Size(max = 100) String identifier, @Size(max = 500) String term) {
	}

	/**
	 * @param descriptionId set when the list gave a description identifier, which was turned into its concept
	 * @param suppliedTerm the term the list gave, if any
	 * @param termDiffers true when the list gave a term that is neither the preferred term nor the
	 *        fully specified name. It may still be a synonym, so this is a prompt to look, not an error.
	 */
	public record Found(String conceptId, String preferredTerm, String fullySpecifiedName, String language,
			String descriptionId, String suppliedTerm, boolean termDiffers) {
	}

	public record Inactive(String conceptId, String term, String reason, String descriptionId,
			List<Replacement> replacements) {
	}

	public record Rejected(String identifier, Reason reason) {
	}

	public enum Reason {
		/** Not a number of the right length. */
		NOT_AN_IDENTIFIER,
		/** The check digit is wrong, so a digit was probably mistyped. */
		INVALID_CHECK_DIGIT,
		/** A relationship or other component, not a concept or description. */
		NOT_A_CONCEPT,
		/** A well formed identifier this release does not have, for example one from another extension. */
		NOT_IN_RELEASE
	}
}
