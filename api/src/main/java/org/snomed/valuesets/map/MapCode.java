package org.snomed.valuesets.map;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * A code on either side of a map with the terms stored beside it: a SNOMED CT concept, or a code
 * of the local list.
 *
 * @param fullySpecifiedName empty for a local code
 */
record MapCode(String code, String term, String fullySpecifiedName) {

	static MapCode of(Concept concept) {
		return new MapCode(concept.conceptId(), concept.preferredTerm() == null ? "" : concept.preferredTerm(),
				concept.fullySpecifiedName() == null ? "" : concept.fullySpecifiedName());
	}

	static MapCode local(String code, String term) {
		return new MapCode(code, term, "");
	}
}
