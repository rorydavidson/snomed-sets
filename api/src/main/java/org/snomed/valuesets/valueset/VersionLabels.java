package org.snomed.valuesets.valueset;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The rules for a version's label, the name an owner may give it instead of its number. */
final class VersionLabels {

	static final int MAX_LENGTH = 40;
	/**
	 * Letters, digits and spaces, with the punctuation dates and release names use. It must start
	 * with a letter or digit.
	 */
	private static final String ALLOWED = "[\\p{L}\\p{N}][\\p{L}\\p{N} ._/:()+-]*";

	private VersionLabels() {
	}

	/** The label with spaces tidied, or null when it is blank. A 400 if it breaks the rules. */
	static String tidy(String label) {
		if (label == null || label.isBlank()) {
			return null;
		}
		String tidy = label.strip().replaceAll("\\s+", " ");
		if (tidy.length() > MAX_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A version label can be at most " + MAX_LENGTH + " characters.");
		}
		if (!tidy.matches(ALLOWED)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A version label can use letters, digits, spaces and . _ / : ( ) + -, and must start with a letter or digit.");
		}
		return tidy;
	}
}
