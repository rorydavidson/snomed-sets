package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EclTermsTests {

	private static final Map<String, String> TERMS = Map.of(
			"195967001", "Asthma",
			"233678006", "Childhood asthma",
			"363698007", "Finding site",
			"39607008", "Lung structure");

	@Test
	void addsTheTermAfterEachBareIdentifier() {
		assertThat(EclTerms.addTerms("<< 195967001 MINUS <<233678006", TERMS))
				.isEqualTo("<< 195967001 |Asthma| MINUS <<233678006 |Childhood asthma|");
	}

	@Test
	void leavesIdentifiersThatAlreadyHaveATerm() {
		String ecl = "<< 195967001 |Asthma| OR << 233678006|my own wording|";

		assertThat(EclTerms.conceptIdsWithoutTerm(ecl)).isEmpty();
		assertThat(EclTerms.addTerms(ecl, TERMS)).isEqualTo(ecl);
	}

	@Test
	void handlesAttributesAndBrackets() {
		assertThat(EclTerms.addTerms("< 195967001: 363698007 = (<< 39607008)", TERMS))
				.isEqualTo("< 195967001 |Asthma|: 363698007 |Finding site| = (<< 39607008 |Lung structure|)");
	}

	@Test
	void ignoresDigitsThatAreNotConceptIdentifiers() {
		String ecl = "< 373873005 |Product 195967001 strength|: 1142135004 |Has strength| >= #195967001, "
				+ "[1..3] 127489000 = *  {{ term = \"233678006\" }}";

		assertThat(EclTerms.conceptIdsWithoutTerm(ecl)).containsExactly("127489000");
	}

	@Test
	void leavesUnknownIdentifiersAndUnusableTermsAlone() {
		assertThat(EclTerms.addTerms("<< 195967001 OR << 999999999 OR << 123456789",
				Map.of("195967001", "Asthma", "123456789", "Odd | term")))
				.isEqualTo("<< 195967001 |Asthma| OR << 999999999 OR << 123456789");
	}

	@Test
	void reportsEachIdentifierOnce() {
		assertThat(EclTerms.conceptIdsWithoutTerm("<< 195967001 OR < 233678006 OR 195967001"))
				.containsExactly("195967001", "233678006");
	}
}
