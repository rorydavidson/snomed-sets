package org.snomed.valuesets.valueset;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * A published version written in the layout of the SNOMED CT Global Patient Set file:
 * tab-separated, one concept per line, with a header line.
 *
 * @param fileName a safe name to offer the browser
 * @param content the whole file
 */
public record GpsFile(String fileName, String content) {

	private static final String HEADER = "SNOMED CT ID\tFSN (en-us)\tPT (en-us)\tActive\n";
	// Only active concepts can be published, so every concept in a version was active at that time.
	private static final String ACTIVE = "1";

	static GpsFile of(ValueSet valueSet, int versionNumber, Collection<Concept> concepts) {
		StringBuilder content = new StringBuilder(HEADER);
		for (Concept concept : concepts) {
			content.append(concept.conceptId()).append('\t')
					.append(cell(concept.fullySpecifiedName())).append('\t')
					.append(cell(concept.preferredTerm())).append('\t')
					.append(ACTIVE).append('\n');
		}
		return new GpsFile(fileNameFor(valueSet.getName(), versionNumber), content.toString());
	}

	/** A term must not break the row it is in. */
	private static String cell(String term) {
		return term == null ? "" : term.replaceAll("[\\t\\r\\n]+", " ");
	}

	/** Lower-case ASCII letters, digits and hyphens only, so the name is safe in a response header. */
	private static String fileNameFor(String valueSetName, int versionNumber) {
		String slug = Normalizer.normalize(valueSetName, Normalizer.Form.NFKD)
				.replaceAll("[^A-Za-z0-9]+", "-")
				.replaceAll("^-+|-+$", "")
				.toLowerCase(Locale.ROOT);
		return (slug.isEmpty() ? "value-set" : slug) + "-v" + versionNumber + ".tsv";
	}
}
