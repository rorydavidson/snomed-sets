package org.snomed.valuesets.map;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.valueset.ValueSetDefinition;

/**
 * What the owner decided for one row.
 *
 * @param targets the SNOMED CT concepts the source maps to, in the order to show them. Only
 *        identifiers are accepted; the terms are looked up in Snowstorm. Empty to leave the row unmapped.
 * @param noMap true to record that nothing fits, in which case the targets are ignored; false when left out
 * @param note an optional remark kept with the row
 */
public record RowUpdate(@Size(max = CodeMapService.MAX_TARGETS) List<@NotNull @Valid Target> targets, Boolean noMap,
		@Size(max = 500) String note) {

	public RowUpdate {
		targets = targets == null ? List.of() : targets;
		noMap = Boolean.TRUE.equals(noMap);
		note = note == null || note.isBlank() ? null : note.strip();
	}

	/** @param relationship Equivalent when left out */
	public record Target(@NotNull @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String conceptId,
			CodeMapTarget.Relationship relationship) {

		public Target {
			relationship = relationship == null ? CodeMapTarget.Relationship.EQUIVALENT : relationship;
		}
	}
}
