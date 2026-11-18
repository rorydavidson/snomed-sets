package org.snomed.valuesets.valueset;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

	/**
	 * The name a new version gets unless its owner gives another: the date of the release it is
	 * built on, such as 2026-07-01. If another version of the set already has that name, a number
	 * is added, as in 2026-07-01 (2). Null for a set on a live branch, which has no release date.
	 *
	 * @param taken the labels the set's other versions already have
	 */
	static String defaultFor(String branch, Collection<String> taken) {
		String release = ValueSet.releaseOf(branch);
		if (release == null) {
			return null;
		}
		Set<String> used = taken.stream()
				.filter(Objects::nonNull)
				.map(label -> label.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
		String label = release;
		for (int n = 2; used.contains(label.toLowerCase(Locale.ROOT)); n++) {
			label = release + " (" + n + ")";
		}
		return label;
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
