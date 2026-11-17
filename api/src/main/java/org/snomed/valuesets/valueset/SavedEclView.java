package org.snomed.valuesets.valueset;

import java.time.Instant;

/**
 * What the browser is told about a saved expression.
 *
 * @param codeSystemVersion the release it was checked on, or null for a live branch
 * @param exact true when, at the time, it matched exactly the active concepts in the list
 * @param current false when the list has changed since it was checked. Only the owner is ever
 *        shown one that is not current, because it may describe their unpublished draft.
 */
public record SavedEclView(String ecl, String codeSystemVersion, String branch, boolean exact, int conceptCount,
		long missingCount, long extraCount, int inactiveCount, Instant savedAt, boolean current) {

	static SavedEclView of(SavedEcl saved, boolean current) {
		return new SavedEclView(saved.getEcl(), ValueSet.releaseOf(saved.getBranch()), saved.getBranch(),
				saved.isExactMatch(), saved.getConceptCount(), saved.getMissingCount(), saved.getExtraCount(),
				saved.getInactiveCount(), saved.getSavedAt(), current);
	}
}
