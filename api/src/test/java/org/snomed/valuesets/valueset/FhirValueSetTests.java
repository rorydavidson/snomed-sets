package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FhirValueSetTests {

	@Test
	void makesAComputableNameThatStartsWithACapitalLetter() {
		assertThat(FhirValueSet.computableName("Childhood asthma (v2)")).isEqualTo("ChildhoodAsthmaV2");
		assertThat(FhirValueSet.computableName("2026 cohort")).isEqualTo("ValueSet2026Cohort");
		assertThat(FhirValueSet.computableName("!!!")).isEqualTo("ValueSet");
	}

	@Test
	void writesTheSnomedVersionUriOnlyWhenEditionAndReleaseAreKnown() {
		assertThat(FhirValueSet.snomedVersion("51000202101", "2026-03-15"))
				.isEqualTo("http://snomed.info/sct/51000202101/version/20260315");
		assertThat(FhirValueSet.snomedVersion(null, "2026-03-15")).isNull();
		assertThat(FhirValueSet.snomedVersion("900000000000207008", null)).isNull();
	}
}
