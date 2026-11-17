package org.snomed.valuesets.snowstorm;

import java.util.List;

/** One page of the concepts an ECL expression expands to. */
public record ExpansionPage(List<Concept> items, long total, int offset, int limit) {

	/**
	 * @param language the language of the preferred term as Snowstorm reports it, for example "en".
	 *        The preferred term is the one preferred in US English where the concept has one.
	 * @param active false only for a concept a hand-picked list kept after a release made it inactive
	 */
	public record Concept(String conceptId, String preferredTerm, String fullySpecifiedName, String language,
			boolean active) {

		/** An active concept, which is what Snowstorm returns and what most lists hold. */
		public Concept(String conceptId, String preferredTerm, String fullySpecifiedName, String language) {
			this(conceptId, preferredTerm, fullySpecifiedName, language, true);
		}

		/** The same concept, flagged as inactive in the release the list is now on. */
		public Concept asInactive() {
			return new Concept(conceptId, preferredTerm, fullySpecifiedName, language, false);
		}
	}
}
