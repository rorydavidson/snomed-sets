package org.snomed.valuesets.map;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;

/**
 * A map written as a flat file: one line for each source and target pair, and one line for a
 * source with no target.
 *
 * @param fileName a safe name to offer the browser
 * @param content the whole file
 */
public record MapFile(String fileName, String content, MediaType mediaType) {

	public enum Format { CSV, TSV }

	private static final List<String> HEADER = List.of("Source code", "Source term", "Target SNOMED CT ID",
			"Target term", "Relationship", "Status", "Note");

	static MapFile of(CodeMap map, List<CodeMapRow> rows, Format format) {
		StringBuilder content = new StringBuilder();
		line(content, format, HEADER);
		for (CodeMapRow row : rows) {
			// A suggestion nobody has accepted is not part of the map, so the row is written as unmapped.
			boolean mapped = row.getStatus() == CodeMapRow.Status.MAPPED;
			String status = (row.getStatus() == CodeMapRow.Status.SUGGESTED ? CodeMapRow.Status.UNMAPPED : row.getStatus()).name();
			String note = row.getNote() == null ? "" : row.getNote();
			if (!mapped || row.getTargets().isEmpty()) {
				line(content, format, List.of(row.getSourceCode(), row.getSourceTerm(), "", "", "", status, note));
				continue;
			}
			for (CodeMapTarget target : row.getTargets()) {
				line(content, format, List.of(row.getSourceCode(), row.getSourceTerm(), target.getCode(), target.getTerm(),
						target.getRelationship().name(), status, note));
			}
		}
		boolean csv = format == Format.CSV;
		return new MapFile(fileNameFor(map.getName(), csv ? "csv" : "tsv"), content.toString(),
				csv ? new MediaType("text", "csv") : new MediaType("text", "tab-separated-values"));
	}

	private static void line(StringBuilder content, Format format, List<String> cells) {
		for (int index = 0; index < cells.size(); index++) {
			if (index > 0) {
				content.append(format == Format.CSV ? ',' : '\t');
			}
			content.append(format == Format.CSV ? csvCell(cells.get(index)) : tsvCell(cells.get(index)));
		}
		content.append(format == Format.CSV ? "\r\n" : "\n");
	}

	/**
	 * Quoted as RFC 4180 asks. A cell that a spreadsheet would read as a formula is prefixed with an
	 * apostrophe, so opening the file cannot run anything. The tab-separated file is left as it is,
	 * for programs that need the codes exactly.
	 */
	static String csvCell(String value) {
		if (value.isEmpty()) {
			return "";
		}
		String safe = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
		return "\"" + safe.replace("\"", "\"\"") + "\"";
	}

	/** A value must not break the row it is in. */
	private static String tsvCell(String value) {
		return value.replaceAll("[\\t\\r\\n]+", " ");
	}

	/** Lower-case ASCII letters, digits and hyphens only, so the name is safe in a response header. */
	private static String fileNameFor(String mapName, String extension) {
		String slug = Normalizer.normalize(mapName, Normalizer.Form.NFKD)
				.replaceAll("[^A-Za-z0-9]+", "-")
				.replaceAll("^-+|-+$", "")
				.toLowerCase(Locale.ROOT);
		return (slug.isEmpty() ? "map" : slug) + "-draft." + extension;
	}
}
