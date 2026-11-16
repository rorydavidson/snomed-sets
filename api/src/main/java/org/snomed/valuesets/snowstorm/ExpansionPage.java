package org.snomed.valuesets.snowstorm;

import java.util.List;

/** One page of the concepts an ECL expression expands to. */
public record ExpansionPage(List<Concept> items, long total, int offset, int limit) {

	/**
	 * @param language the language of the preferred term as Snowstorm reports it, for example "en".
	 *        The preferred term is the one preferred in US English where the concept has one.
	 */
	public record Concept(String conceptId, String preferredTerm, String fullySpecifiedName, String language) {
	}
}
