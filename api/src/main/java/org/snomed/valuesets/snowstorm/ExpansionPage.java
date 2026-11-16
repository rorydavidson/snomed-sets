package org.snomed.valuesets.snowstorm;

import java.util.List;

/** One page of the concepts an ECL expression expands to. */
public record ExpansionPage(List<Concept> items, long total, int offset, int limit) {

	public record Concept(String conceptId, String preferredTerm, String fullySpecifiedName) {
	}
}
