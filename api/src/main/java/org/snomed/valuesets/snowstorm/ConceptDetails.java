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

	/**
	 * @param preferredIn the dialects in which this is the preferred term, for example en-US.
	 *        A language reference set this application has no name for is given by its identifier.
	 */
	public record Description(String term, String type, String language, List<String> preferredIn) {
	}

	public record Relationship(String typeId, String typeTerm, String targetId, String targetTerm, int group) {
	}
}
