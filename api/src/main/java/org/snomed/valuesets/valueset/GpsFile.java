package org.snomed.valuesets.valueset;

import java.text.Normalizer;
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

	static GpsFile of(DownloadContent download) {
		StringBuilder content = new StringBuilder(HEADER);
		for (Concept concept : download.concepts()) {
			content.append(concept.conceptId()).append('\t')
					.append(cell(concept.fullySpecifiedName())).append('\t')
					.append(cell(concept.preferredTerm())).append('\t')
					.append(concept.active() ? "1" : "0").append('\n');
		}
		return new GpsFile(fileNameFor(download.valueSet().getName(), download.fileLabel(), "tsv"), content.toString());
	}

	/** A term must not break the row it is in. */
	private static String cell(String term) {
		return term == null ? "" : term.replaceAll("[\\t\\r\\n]+", " ");
	}

	/** Lower-case ASCII letters, digits and hyphens only, so the name is safe in a response header. */
	/** @param label v3 for a version, or draft */
	static String fileNameFor(String valueSetName, String label, String extension) {
		String slug = Normalizer.normalize(valueSetName, Normalizer.Form.NFKD)
				.replaceAll("[^A-Za-z0-9]+", "-")
				.replaceAll("^-+|-+$", "")
				.toLowerCase(Locale.ROOT);
		return (slug.isEmpty() ? "value-set" : slug) + "-" + label + "." + extension;
	}
}
