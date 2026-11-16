package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds concept identifiers in an ECL expression that were written without a term,
 * as in {@code << 195967001}, and writes the term after them: {@code << 195967001 |Asthma|}.
 *
 * This is a light scan, not an ECL parser. It only has to tell identifiers apart from
 * the other places digits can appear: inside an existing term, inside a quoted search
 * string, and in numeric values such as {@code #500}.
 */
final class EclTerms {

	private static final int SHORTEST_CONCEPT_ID = 6;
	private static final int LONGEST_CONCEPT_ID = 18;

	private EclTerms() {
	}

	/** Concept identifiers that have no term after them, in order of first appearance. */
	static Set<String> conceptIdsWithoutTerm(String ecl) {
		Set<String> conceptIds = new LinkedHashSet<>();
		bareIdentifiers(ecl).forEach(found -> conceptIds.add(found.conceptId()));
		return conceptIds;
	}

	/** Adds the term after each identifier that has none. Identifiers with no known term are left alone. */
	static String addTerms(String ecl, Map<String, String> termsByConceptId) {
		StringBuilder result = new StringBuilder(ecl);
		List<BareIdentifier> found = bareIdentifiers(ecl);
		// Back to front, so earlier positions stay valid while text is inserted.
		for (int i = found.size() - 1; i >= 0; i--) {
			String term = termsByConceptId.get(found.get(i).conceptId());
			// A term containing the delimiter could not be written as valid ECL.
			if (term != null && !term.isBlank() && !term.contains("|")) {
				result.insert(found.get(i).end(), " |" + term + "|");
			}
		}
		return result.toString();
	}

	private record BareIdentifier(String conceptId, int end) {
	}

	private static List<BareIdentifier> bareIdentifiers(String ecl) {
		List<BareIdentifier> found = new ArrayList<>();
		int i = 0;
		while (i < ecl.length()) {
			char c = ecl.charAt(i);
			if (c == '|') {
				i = endOfDelimited(ecl, i, '|');
			}
			else if (c == '"') {
				i = endOfDelimited(ecl, i, '"');
			}
			else if (c == '#') {
				i = endOfNumber(ecl, i + 1);
			}
			else if (Character.isDigit(c)) {
				int end = endOfDigits(ecl, i);
				if (isConceptId(ecl, i, end) && !termFollows(ecl, end)) {
					found.add(new BareIdentifier(ecl.substring(i, end), end));
				}
				i = end;
			}
			else {
				i++;
			}
		}
		return found;
	}

	/** The position after the closing delimiter, or the end of the text if it is never closed. */
	private static int endOfDelimited(String ecl, int start, char delimiter) {
		for (int i = start + 1; i < ecl.length(); i++) {
			if (ecl.charAt(i) == '\\' && delimiter == '"') {
				i++;
			}
			else if (ecl.charAt(i) == delimiter) {
				return i + 1;
			}
		}
		return ecl.length();
	}

	private static int endOfNumber(String ecl, int start) {
		int i = start;
		while (i < ecl.length() && (Character.isDigit(ecl.charAt(i)) || ecl.charAt(i) == '.' || ecl.charAt(i) == '-')) {
			i++;
		}
		return i;
	}

	private static int endOfDigits(String ecl, int start) {
		int i = start;
		while (i < ecl.length() && Character.isDigit(ecl.charAt(i))) {
			i++;
		}
		return i;
	}

	private static boolean isConceptId(String ecl, int start, int end) {
		int length = end - start;
		boolean partOfAWord = (start > 0 && isWordCharacter(ecl.charAt(start - 1)))
				|| (end < ecl.length() && isWordCharacter(ecl.charAt(end)));
		return length >= SHORTEST_CONCEPT_ID && length <= LONGEST_CONCEPT_ID && !partOfAWord;
	}

	private static boolean isWordCharacter(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '.';
	}

	private static boolean termFollows(String ecl, int position) {
		int i = position;
		while (i < ecl.length() && Character.isWhitespace(ecl.charAt(i))) {
			i++;
		}
		return i < ecl.length() && ecl.charAt(i) == '|';
	}
}
