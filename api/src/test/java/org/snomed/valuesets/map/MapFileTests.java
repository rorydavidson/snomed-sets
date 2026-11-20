package org.snomed.valuesets.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MapFileTests {

	@Test
	void quotesCellsAndDoublesQuotesInside() {
		assertThat(MapFile.csvCell("Asthma, \"severe\"")).isEqualTo("\"Asthma, \"\"severe\"\"\"");
		assertThat(MapFile.csvCell("")).isEmpty();
	}

	@Test
	void neutralisesCellsASpreadsheetWouldReadAsAFormula() {
		assertThat(MapFile.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
		assertThat(MapFile.csvCell("-12")).isEqualTo("\"'-12\"");
	}
}
