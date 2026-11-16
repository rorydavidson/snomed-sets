package org.snomed.valuesets.snowstorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Runs against a real Snowstorm that has a SNOMED CT release loaded. Skipped unless
 * SNOWSTORM_LIVE_URL is set, for example SNOWSTORM_LIVE_URL=http://localhost:8080 mvn test
 */
@EnabledIfEnvironmentVariable(named = "SNOWSTORM_LIVE_URL", matches = ".+")
class SnowstormLiveTests {

	private final SnowstormClient client = new SnowstormClient(RestClient.builder(),
			new SnowstormProperties(System.getenv("SNOWSTORM_LIVE_URL"), "snomed-value-sets-tests"));

	@Test
	void expandsAsthmaAndItsSubtypes() {
		ExpansionPage page = client.expand("<< 195967001 |Asthma|", "MAIN", 0, 5);

		assertThat(page.total()).isGreaterThan(5);
		assertThat(page.items()).hasSize(5).allSatisfy(concept -> {
			assertThat(concept.conceptId()).matches("\\d+");
			assertThat(concept.preferredTerm()).isNotBlank();
			assertThat(concept.fullySpecifiedName()).isNotBlank();
		});
	}

	@Test
	void pagesDoNotOverlap() {
		ExpansionPage first = client.expand("<< 195967001 |Asthma|", "MAIN", 0, 5);
		ExpansionPage second = client.expand("<< 195967001 |Asthma|", "MAIN", 5, 5);

		assertThat(second.items()).doesNotContainAnyElementsOf(first.items());
	}

	@Test
	void invalidEclIsReportedAsCausedByTheRequest() {
		assertThatThrownBy(() -> client.expand("this is not ecl <<", "MAIN", 0, 5))
				.isInstanceOfSatisfying(SnowstormException.class,
						e -> assertThat(e.isCausedByRequest()).isTrue());
	}
}
