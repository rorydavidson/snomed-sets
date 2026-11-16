package org.snomed.valuesets.snowstorm;

import java.util.List;

/**
 * What Snowstorm knows about one concept, reduced to what a person reads: its active
 * descriptions and its active inferred relationships.
 *
 * @param parents the concepts this one is a kind of
 * @param attributes the other defining relationships, with their role group
 */
public record ConceptDetails(String conceptId, String fullySpecifiedName, String preferredTerm, boolean active,
		String definitionStatus, String moduleId, String effectiveTime, List<Description> descriptions,
		List<Relationship> parents, List<Relationship> attributes) {

	/** @param preferred true when the term is preferred in at least one language reference set */
	public record Description(String term, String type, String language, boolean preferred) {
	}

	public record Relationship(String typeTerm, String targetId, String targetTerm, int group) {
	}
}
