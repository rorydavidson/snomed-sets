package org.snomed.valuesets.map;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the owner decided for one row.
 *
 * @param targets what the source maps to, in the order to show them: SNOMED CT concepts in a map
 *        to SNOMED CT, local codes in a map from it. Only codes are accepted; the terms are looked
 *        up in Snowstorm or in the map's local list. Empty to leave the row unmapped.
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
	public record Target(@NotNull @Size(min = 1, max = 100) String code, CodeMapTarget.Relationship relationship) {

		public Target {
			relationship = relationship == null ? CodeMapTarget.Relationship.EQUIVALENT : relationship;
		}
	}
}
