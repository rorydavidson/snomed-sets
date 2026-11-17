package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SctIdTests {

	@Test
	void realIdentifiersHaveValidCheckDigits() {
		assertThat(SctId.hasValidCheckDigit("195967001")).isTrue();
		assertThat(SctId.hasValidCheckDigit("301484010")).isTrue();
		assertThat(SctId.hasValidCheckDigit("900000000000207008")).isTrue();
	}

	@Test
	void aMistypedDigitOrSwappedPairIsCaught() {
		assertThat(SctId.hasValidCheckDigit("195967002")).isFalse();
		assertThat(SctId.hasValidCheckDigit("195967010")).isFalse();
		assertThat(SctId.hasValidCheckDigit("159967001")).isFalse();
	}

	@Test
	void thePartitionSaysWhatTheIdentifierNames() {
		assertThat(SctId.kindOf("195967001")).isEqualTo(SctId.Kind.CONCEPT);
		assertThat(SctId.kindOf("61000202103")).isEqualTo(SctId.Kind.CONCEPT);
		assertThat(SctId.kindOf("301484010")).isEqualTo(SctId.Kind.DESCRIPTION);
		assertThat(SctId.kindOf("100022")).isEqualTo(SctId.Kind.OTHER);
	}
}
