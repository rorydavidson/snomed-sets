package org.snomed.valuesets.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermMatchServiceTests {

	@Test
	void termsAreComparedWithoutCaseOrExtraSpaces() {
		assertThat(TermMatchService.normalised("  Myocardial   INFARCTION ")).isEqualTo("myocardial infarction");
	}

	@Test
	void anAccentedLetterIsTheSameHoweverItWasTyped() {
		// "é" as one character, and as "e" followed by a combining accent.
		assertThat(TermMatchService.normalised("M\u00e9ni\u00e8re")).isEqualTo(TermMatchService.normalised("Me\u0301nie\u0300re"));
	}
}
