package org.snomed.valuesets.valueset;

import java.util.List;

/**
 * A value set whose concept list has concepts matching a search.
 *
 * @param matchCount how many of its concepts match
 * @param examples the first few matching concepts, in term order, to show why the set was found
 */
public record ConceptSearchHit(long valueSetId, int matchCount, List<Match> examples) {

	public record Match(String conceptId, String term) {
	}
}
