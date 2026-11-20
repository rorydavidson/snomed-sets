package org.snomed.valuesets.snowstorm;

/**
 * A description found by a term search, with the concept it belongs to.
 *
 * @param term the description itself, which may be a synonym rather than the preferred term
 * @param language its language code, for example "en"
 */
public record DescriptionMatch(String term, String language, ExpansionPage.Concept concept) {
}
