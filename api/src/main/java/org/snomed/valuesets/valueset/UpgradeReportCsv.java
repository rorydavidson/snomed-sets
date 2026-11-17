package org.snomed.valuesets.valueset;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.snomed.valuesets.valueset.UpgradePreview.Change;

/**
 * The changes one upgrade made, as a CSV file with a header line.
 *
 * @param fileName a safe name to offer the browser
 * @param content the whole file
 */
public record UpgradeReportCsv(String fileName, String content) {

	private static final String HEADER = "Change,Concept ID,Preferred term,Related concept ID,Related term,Detail\r\n";

	static UpgradeReportCsv of(ValueSet valueSet, ValueSetUpgrade upgrade, List<Change> changes) {
		StringBuilder content = new StringBuilder(HEADER);
		for (Change change : changes) {
			content.append(String.join(",", change.type().name(), cell(change.conceptId()), cell(change.term()),
					cell(change.relatedConceptId()), cell(change.relatedTerm()), cell(change.detail()))).append("\r\n");
		}
		return new UpgradeReportCsv(fileNameFor(valueSet.getName(), upgrade), content.toString());
	}

	/**
	 * Quoted as RFC 4180 asks. A cell that a spreadsheet would read as a formula is prefixed
	 * with an apostrophe, so opening the file cannot run anything.
	 */
	static String cell(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String safe = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
		return "\"" + safe.replace("\"", "\"\"") + "\"";
	}

	/** Lower-case ASCII letters, digits and hyphens only, so the name is safe in a response header. */
	private static String fileNameFor(String valueSetName, ValueSetUpgrade upgrade) {
		String slug = Normalizer.normalize(valueSetName, Normalizer.Form.NFKD)
				.replaceAll("[^A-Za-z0-9]+", "-")
				.replaceAll("^-+|-+$", "")
				.toLowerCase(Locale.ROOT);
		String from = upgrade.getFromVersion() == null ? "live" : upgrade.getFromVersion();
		return (slug.isEmpty() ? "value-set" : slug) + "-upgrade-" + from + "-to-" + upgrade.getToVersion() + ".csv";
	}
}
