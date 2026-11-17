package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * What upgrading a value set to a newer release would change, worked out without saving anything.
 *
 * @param fromVersion the release the value set is on now, or null for the live branch
 * @param conceptCountBefore how many concepts the value set has now
 * @param added for an ECL definition, the concepts it matches only in the new release
 * @param removed for an ECL definition, the concepts it no longer matches
 * @param termChanges concepts that stay, whose preferred term is different in the new release
 * @param inactivated for a hand-picked list, the concepts that are inactive in the new release,
 *        each with the replacements the historical association reference sets suggest
 */
public record UpgradePreview(String codeSystem, ValueSet.DefinitionType type, String fromVersion, String toVersion,
		String fromBranch, String toBranch, long conceptCountBefore, List<Concept> added, List<Concept> removed,
		List<TermChange> termChanges, List<InactiveMember> inactivated) {

	public record TermChange(String conceptId, String oldTerm, String newTerm) {
	}

	/**
	 * @param reason the inactivation reason, for example DUPLICATE; may be null
	 * @param keptBefore true when an earlier upgrade already kept it, flagged as inactive, so the
	 *        owner is likely to want to keep it again
	 */
	public record InactiveMember(String conceptId, String term, String reason, List<Replacement> replacements,
			boolean keptBefore) {
	}

	/**
	 * @param association the historical association that suggests it, for example REPLACED_BY
	 * @param alreadyInSet true when the replacement is already in the list, so accepting it adds nothing
	 */
	public record Replacement(String conceptId, String term, String association, boolean alreadyInSet) {
	}

	/** One line of the report kept when an upgrade is saved. */
	public record Change(ValueSetUpgradeChange.Type type, String conceptId, String term, String relatedConceptId,
			String relatedTerm, String detail) {
	}

	/** Whether there is a newer release to upgrade to. */
	public record Availability(String codeSystem, String currentVersion, String latestVersion, boolean available) {
	}

	/**
	 * A saved upgrade, as listed on the value set.
	 *
	 * @param publishedVersion the version published with the upgrade, or null if it was saved as a draft
	 * @param changeCounts how many report lines of each type the upgrade has
	 */
	public record UpgradeView(long id, String fromVersion, String toVersion, String fromBranch, String toBranch,
			Instant upgradedAt, Integer publishedVersion, Map<ValueSetUpgradeChange.Type, Long> changeCounts) {
	}

	public record Result(ValueSetView valueSet, UpgradeView upgrade) {
	}
}
