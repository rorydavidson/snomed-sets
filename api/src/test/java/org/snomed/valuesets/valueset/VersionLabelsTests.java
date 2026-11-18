package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class VersionLabelsTests {

	@Test
	void defaultsToTheReleaseDateOfTheBranch() {
		assertThat(VersionLabels.defaultFor("MAIN/2026-07-01", List.of())).isEqualTo("2026-07-01");
		assertThat(VersionLabels.defaultFor("MAIN/SNOMEDCT-BE/2026-03-15", List.of("Spring"))).isEqualTo("2026-03-15");
	}

	@Test
	void numbersTheDateWhenAnotherVersionAlreadyHasIt() {
		assertThat(VersionLabels.defaultFor("MAIN/2026-07-01", Arrays.asList("2026-07-01", null)))
				.isEqualTo("2026-07-01 (2)");
		assertThat(VersionLabels.defaultFor("MAIN/2026-07-01", List.of("2026-07-01", "2026-07-01 (2)")))
				.isEqualTo("2026-07-01 (3)");
	}

	@Test
	void hasNoDefaultOnALiveBranch() {
		assertThat(VersionLabels.defaultFor("MAIN", List.of())).isNull();
	}
}
