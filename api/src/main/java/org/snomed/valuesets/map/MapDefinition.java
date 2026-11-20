package org.snomed.valuesets.map;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.valueset.ValueSetDefinition;

/**
 * What a person sends to create a map.
 *
 * @param codeSystem short name of a Snowstorm code system; the International Edition when left out.
 *        It cannot be changed once the map exists.
 * @param codeSystemVersion the release to build the map on, for example 2026-01-01; the latest when left out
 * @param direction to SNOMED CT when left out. It cannot be changed once the map exists.
 * @param rows the local codes, which may be empty and added to later. In a map to SNOMED CT they
 *        are what is mapped; in a map from it they are what targets are chosen from.
 * @param conceptIds the concepts to map, for a map from SNOMED CT; ignored otherwise. Only
 *        identifiers are accepted, and the terms are looked up in Snowstorm.
 */
public record MapDefinition(@NotNull @Valid MapDetails details, CodeMap.Direction direction,
		@Size(max = 100) String codeSystem,
		@Pattern(regexp = ValueSetDefinition.RELEASE_REGEX) String codeSystemVersion,
		@Size(max = CodeMapService.MAX_ROWS) List<@NotNull @Valid SourceRow> rows,
		@Size(max = CodeMapService.MAX_ROWS) List<@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String> conceptIds) {

	public MapDefinition {
		direction = direction == null ? CodeMap.Direction.TO_SNOMED : direction;
		conceptIds = conceptIds == null ? List.of() : conceptIds;
		codeSystem = codeSystem == null ? ValueSetDefinition.INTERNATIONAL_EDITION : codeSystem;
		rows = rows == null ? List.of() : rows;
	}

	/** A local code and the term it has in the local list. The term may be empty. */
	public record SourceRow(@NotNull @Size(min = 1, max = 100) String code, @Size(max = 500) String term) {

		public SourceRow {
			code = code == null ? null : code.strip();
			// A term must stay on one line, so it cannot break a row of a downloaded file.
			term = term == null ? "" : term.replaceAll("\\s+", " ").strip();
		}
	}

	/** More local codes for a map that already exists. */
	public record MoreRows(@NotNull @Size(min = 1, max = CodeMapService.MAX_ROWS) List<@NotNull @Valid SourceRow> rows) {
	}

	/** More concepts to map, for a map from SNOMED CT. */
	public record MoreConcepts(@NotNull @Size(min = 1, max = CodeMapService.MAX_ROWS)
			List<@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String> conceptIds) {
	}
}
