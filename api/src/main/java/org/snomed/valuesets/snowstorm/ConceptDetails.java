package org.snomed.valuesets.snowstorm;

import java.util.List;
import java.util.Map;

/**
 * What Snowstorm knows about one concept, reduced to what a person reads: its active
 * descriptions and its active inferred relationships, with what is needed to say where it
 * comes from and in which language it is shown.
 *
 * @param preferredTerm the preferred term in the edition's language where the concept has one
 * @param preferredTermLanguage the language of that term, for example "no" or "en"
 * @param parents the concepts this one is a kind of
 * @param attributes the other defining relationships, with their role group
 * @param edition the edition the concept was looked up in; null if it could not be told
 * @param moduleName the name of the module the concept belongs to, where it could be looked up
 * @param international true when that module is part of the International Edition rather than a
 *        national extension
 * @param names readable names for the modules and language reference sets the descriptions refer
 *        to, by identifier
 * @param internationalModules which of the modules the concept and its descriptions are in belong to
 *        the International Edition; the others belong to the edition's own extension
 */
public record ConceptDetails(String conceptId, String fullySpecifiedName, String preferredTerm,
		String preferredTermLanguage, boolean active, String definitionStatus, String moduleId, String effectiveTime,
		List<Description> descriptions, List<Relationship> parents, List<Relationship> attributes,
		EditionSummary edition, String moduleName, boolean international, Map<String, String> names,
		List<String> internationalModules) {

	/**
	 * @param preferredIn the language reference sets in which this is the preferred term. US and GB
	 *        English are given as en-US and en-GB; any other by its identifier, named in {@code names}.
	 * @param acceptableIn the same, for where it is an acceptable synonym
	 * @param moduleId the module the description belongs to; a translation is usually in the
	 *        extension's module even when the concept is international
	 */
	public record Description(String term, String type, String language, List<String> preferredIn,
			List<String> acceptableIn, String moduleId) {
	}

	public record Relationship(String typeId, String typeTerm, String targetId, String targetTerm, int group) {
	}

	/** @param language the language terms are shown in first, for example "no" */
	public record EditionSummary(String shortName, String name, String language, Map<String, String> languages) {
	}

	/** The same concept, with where it comes from and readable names for the identifiers it uses. */
	public ConceptDetails withContext(Edition edition, Map<String, String> names) {
		EditionSummary summary = edition == null ? null
				: new EditionSummary(edition.shortName(), edition.name(), edition.language(), edition.languages());
		java.util.function.Predicate<String> isInternational = module -> edition == null
				? INTERNATIONAL_MODULES.contains(module)
				: edition.isInternational(module);
		List<String> international = java.util.stream.Stream
				.concat(java.util.stream.Stream.of(moduleId), descriptions.stream().map(Description::moduleId))
				.filter(java.util.Objects::nonNull)
				.distinct()
				.filter(isInternational)
				.toList();
		return new ConceptDetails(conceptId, fullySpecifiedName, preferredTerm, preferredTermLanguage, active,
				definitionStatus, moduleId, effectiveTime, descriptions, parents, attributes, summary,
				names.get(moduleId), isInternational.test(moduleId), names, international);
	}

	/** Used only when the edition is not known, so the modules it holds cannot be asked about. */
	private static final List<String> INTERNATIONAL_MODULES = List.of("900000000000207008", Edition.MODEL_MODULE,
			"449080006");
}
