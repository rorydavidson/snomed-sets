package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpgradeReportCsvTests {

	@Test
	void quotesCellsAndDoublesQuotesInside() {
		assertThat(UpgradeReportCsv.cell("Asthma, \"severe\"")).isEqualTo("\"Asthma, \"\"severe\"\"\"");
		assertThat(UpgradeReportCsv.cell(null)).isEmpty();
	}

	@Test
	void neutralisesCellsASpreadsheetWouldReadAsAFormula() {
		assertThat(UpgradeReportCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
		assertThat(UpgradeReportCsv.cell("+1")).isEqualTo("\"'+1\"");
		assertThat(UpgradeReportCsv.cell("@sum")).isEqualTo("\"'@sum\"");
	}
}
