package org.snomed.valuesets.snowstorm;

/**
 * One active description of a concept: its fully specified name or a synonym, in any language.
 *
 * @param language its language code, for example "en"
 */
public record ConceptTerm(String conceptId, String term, String language) {
}
