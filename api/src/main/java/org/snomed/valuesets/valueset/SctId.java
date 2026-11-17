package org.snomed.valuesets.valueset;

/**
 * Checks the shape of a SNOMED CT identifier: its check digit and which kind of component it
 * names. Nothing here asks Snowstorm whether the identifier exists.
 */
final class SctId {

	enum Kind { CONCEPT, DESCRIPTION, OTHER }

	private static final int[][] MULTIPLY = {
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 }, { 1, 2, 3, 4, 0, 6, 7, 8, 9, 5 }, { 2, 3, 4, 0, 1, 7, 8, 9, 5, 6 },
			{ 3, 4, 0, 1, 2, 8, 9, 5, 6, 7 }, { 4, 0, 1, 2, 3, 9, 5, 6, 7, 8 }, { 5, 9, 8, 7, 6, 0, 4, 3, 2, 1 },
			{ 6, 5, 9, 8, 7, 1, 0, 4, 3, 2 }, { 7, 6, 5, 9, 8, 2, 1, 0, 4, 3 }, { 8, 7, 6, 5, 9, 3, 2, 1, 0, 4 },
			{ 9, 8, 7, 6, 5, 4, 3, 2, 1, 0 } };
	private static final int[][] PERMUTE = {
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 }, { 1, 5, 7, 6, 2, 8, 3, 0, 9, 4 }, { 5, 8, 0, 3, 7, 9, 6, 1, 4, 2 },
			{ 8, 9, 1, 6, 0, 4, 3, 5, 2, 7 }, { 9, 4, 5, 3, 1, 2, 6, 8, 7, 0 }, { 4, 2, 8, 6, 5, 7, 3, 9, 0, 1 },
			{ 2, 7, 9, 3, 8, 0, 6, 4, 1, 5 }, { 7, 0, 4, 6, 9, 1, 3, 2, 5, 8 } };

	private SctId() {
	}

	static boolean hasValidShape(String identifier) {
		return identifier.matches(ValueSetDefinition.CONCEPT_ID_REGEX);
	}

	/** SNOMED CT identifiers end in a Verhoeff check digit, which catches most mistyped digits. */
	static boolean hasValidCheckDigit(String identifier) {
		int check = 0;
		for (int position = 0; position < identifier.length(); position++) {
			int digit = identifier.charAt(identifier.length() - 1 - position) - '0';
			check = MULTIPLY[check][PERMUTE[position % 8][digit]];
		}
		return check == 0;
	}

	/**
	 * The second digit from the right says what the identifier names; the third from the right
	 * is 1 for an extension's identifier and 0 for the International Edition's.
	 */
	static Kind kindOf(String identifier) {
		return switch (identifier.charAt(identifier.length() - 2)) {
			case '0' -> Kind.CONCEPT;
			case '1' -> Kind.DESCRIPTION;
			default -> Kind.OTHER;
		};
	}
}
