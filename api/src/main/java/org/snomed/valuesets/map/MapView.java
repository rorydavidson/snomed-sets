package org.snomed.valuesets.map;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.snomed.valuesets.valueset.ValueSet;

/**
 * What the browser is told about a map. Only its owner is ever told, as maps are private.
 *
 * @param codeSystemVersion the release the map is built on, for example 2026-01-01, or null when
 *        it is on a live branch
 * @param counts how far the mapping has got
 * @param localCodeCount for a map from SNOMED CT, how many local codes there are to choose targets
 *        from; 0 for a map to SNOMED CT, whose local codes are its rows
 */
public record MapView(long id, String name, String description, CodeMap.Direction direction, String codeSystem,
		String branch, String codeSystemVersion, String localSystemName, String localSystemUri,
		String localSystemVersion, Instant updatedAt, Counts counts, int localCodeCount) {

	static MapView of(CodeMap map, Counts counts, int localCodeCount) {
		return new MapView(map.getId(), map.getName(), map.getDescription(), map.getDirection(), map.getCodeSystem(),
				map.getBranch(), ValueSet.releaseOf(map.getBranch()), map.getLocalSystemName(), map.getLocalSystemUri(),
				map.getLocalSystemVersion(), map.getUpdatedAt(), counts, localCodeCount);
	}

	/** How many rows a map has, and how many are in each state. */
	public record Counts(long total, long unmapped, long suggested, long mapped, long noMap) {

		static final Counts NONE = new Counts(0, 0, 0, 0, 0);

		static Counts of(Map<CodeMapRow.Status, Long> byStatus) {
			long unmapped = byStatus.getOrDefault(CodeMapRow.Status.UNMAPPED, 0L);
			long suggested = byStatus.getOrDefault(CodeMapRow.Status.SUGGESTED, 0L);
			long mapped = byStatus.getOrDefault(CodeMapRow.Status.MAPPED, 0L);
			long noMap = byStatus.getOrDefault(CodeMapRow.Status.NO_MAP, 0L);
			return new Counts(unmapped + suggested + mapped + noMap, unmapped, suggested, mapped, noMap);
		}
	}

	/**
	 * One row with its targets.
	 *
	 * @param fullySpecifiedName of a SNOMED CT source; empty for a local code
	 * @param termChecked true once the term has been looked for on the other side of the map
	 */
	public record RowView(long id, String code, String term, String fullySpecifiedName, CodeMapRow.Status status, String note,
			boolean termChecked, List<TargetView> targets) {

		static RowView of(CodeMapRow row) {
			return new RowView(row.getId(), row.getSourceCode(), row.getSourceTerm(), row.getSourceFsn(), row.getStatus(), row.getNote(),
					row.isTermChecked(), row.getTargets().stream().map(TargetView::of).toList());
		}
	}

	public record TargetView(String code, String term, String fullySpecifiedName,
			CodeMapTarget.Relationship relationship, CodeMapTarget.Origin origin) {

		static TargetView of(CodeMapTarget target) {
			return new TargetView(target.getCode(), target.getTerm(), target.getFullySpecifiedName(),
					target.getRelationship(), target.getOrigin());
		}
	}
}
