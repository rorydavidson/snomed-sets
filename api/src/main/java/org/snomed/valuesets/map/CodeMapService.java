package org.snomed.valuesets.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.snomed.valuesets.map.CodeMapTarget.Relationship;
import org.snomed.valuesets.map.MapDefinition.SourceRow;
import org.snomed.valuesets.map.MapView.Counts;
import org.snomed.valuesets.map.MapView.RowView;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.CodeSystemVersion;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * All rules about who may see or change a map live here. Maps are private, so every call is for
 * the owner; anyone else gets a 404 as if the map did not exist.
 *
 * Calls to Snowstorm are made outside database transactions, so a slow Snowstorm does not hold a
 * database connection.
 */
@Service
public class CodeMapService {

	public static final int MAX_ROWS = 5000;
	public static final int MAX_TARGETS = 10;
	private static final int DUPLICATES_NAMED = 10;

	private final CodeMapRepository maps;
	private final CodeMapRowRepository rows;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;
	private final JdbcTemplate jdbc;

	public CodeMapService(CodeMapRepository maps, CodeMapRowRepository rows, SnowstormClient snowstorm,
			TransactionTemplate transaction, JdbcTemplate jdbc) {
		this.maps = maps;
		this.rows = rows;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
		this.jdbc = jdbc;
	}

	public List<MapView> list(CurrentUser user) {
		List<CodeMap> owned = maps.findByOwnerSubjectOrderByUpdatedAtDesc(user.subject());
		Map<Long, Counts> counts = countsOf(owned.stream().map(CodeMap::getId).toList());
		return owned.stream().map(map -> MapView.of(map, counts.getOrDefault(map.getId(), Counts.NONE))).toList();
	}

	public MapView get(long id, CurrentUser user) {
		return view(owned(id, user));
	}

	public MapView create(MapDefinition definition, CurrentUser user) {
		requireNoRepeats(definition.rows(), Set.of());
		String branch = definition.codeSystemVersion() == null
				? releaseBranchOf(definition.codeSystem())
				: releaseBranchOf(definition.codeSystem(), definition.codeSystemVersion());
		CodeMap created = transaction.execute(status -> {
			CodeMap map = maps.save(new CodeMap(CodeMap.Direction.TO_SNOMED, definition.codeSystem(), branch,
					definition.details(), user.subject(), user.name()));
			insertRows(map.getId(), definition.rows(), 0);
			return map;
		});
		return view(created);
	}

	public MapView update(long id, MapDetails details, CurrentUser user) {
		CodeMap map = owned(id, user);
		map.update(details);
		return view(maps.save(map));
	}

	public void delete(long id, CurrentUser user) {
		maps.delete(owned(id, user));
	}

	/** Every row of the map as it is being worked on, in the order the rows were given. */
	public List<RowView> rows(long id, CurrentUser user) {
		CodeMap map = owned(id, user);
		return workingRows(map).stream().map(RowView::of).toList();
	}

	/** Adds local codes to the end of the map. Codes the map already has are refused, not merged. */
	public MapView addRows(long id, MapDefinition.MoreRows more, CurrentUser user) {
		CodeMap map = owned(id, user);
		return transaction.execute(status -> {
			Set<String> existing = new HashSet<>(rows.findWorkingCodes(map.getId()));
			if (existing.size() + more.rows().size() > MAX_ROWS) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"A map can hold at most " + MAX_ROWS + " codes. This one has " + existing.size() + ".");
			}
			requireNoRepeats(more.rows(), existing);
			insertRows(map.getId(), more.rows(), rows.findLastWorkingPosition(map.getId()) + 1);
			map.touch();
			return view(maps.save(map));
		});
	}

	/** Sets one row's targets, or records that it has no map. */
	public RowView updateRow(long id, long rowId, RowUpdate update, CurrentUser user) {
		CodeMap map = owned(id, user);
		// LinkedHashMap, so the targets keep the order they were sent in.
		Map<Concept, Relationship> chosen = update.noMap() ? Map.of() : conceptsOf(update.targets(), map.getBranch());
		return transaction.execute(status -> {
			CodeMapRow row = workingRow(map, rowId);
			if (update.noMap()) {
				row.markNoMap(update.note());
			}
			else {
				row.mapTo(chosen, update.note());
			}
			map.touch();
			maps.save(map);
			return RowView.of(rows.save(row));
		});
	}

	public void deleteRow(long id, long rowId, CurrentUser user) {
		CodeMap map = owned(id, user);
		transaction.executeWithoutResult(status -> {
			rows.delete(workingRow(map, rowId));
			map.touch();
			maps.save(map);
		});
	}

	/**
	 * Accepts the suggestion of every row that has exactly one. A row with several is left for the
	 * owner to choose between.
	 *
	 * @return how many rows were accepted
	 */
	public int acceptSingleSuggestions(long id, CurrentUser user) {
		CodeMap map = owned(id, user);
		return transaction.execute(status -> {
			List<CodeMapRow> single = rows.findByMapIdAndVersionNumberAndStatus(map.getId(), CodeMapRow.WORKING,
					CodeMapRow.Status.SUGGESTED).stream().filter(row -> row.getTargets().size() == 1).toList();
			single.forEach(row -> {
				row.acceptSuggestions();
				rows.save(row);
			});
			if (!single.isEmpty()) {
				map.touch();
				maps.save(map);
			}
			return single.size();
		});
	}

	public MapFile download(long id, MapFile.Format format, CurrentUser user) {
		CodeMap map = owned(id, user);
		return MapFile.of(map, workingRows(map), format);
	}

	/** A map the person owns, or a 404 as if it did not exist. */
	CodeMap owned(long id, CurrentUser user) {
		return maps.findByIdAndOwnerSubject(id, user.subject())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Map not found"));
	}

	List<CodeMapRow> workingRows(CodeMap map) {
		return rows.findByMapIdAndVersionNumberOrderByPosition(map.getId(), CodeMapRow.WORKING);
	}

	private CodeMapRow workingRow(CodeMap map, long rowId) {
		return rows.findByIdAndMapIdAndVersionNumber(rowId, map.getId(), CodeMapRow.WORKING)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Row not found"));
	}

	private MapView view(CodeMap map) {
		return MapView.of(map, countsOf(List.of(map.getId())).getOrDefault(map.getId(), Counts.NONE));
	}

	private Map<Long, Counts> countsOf(Collection<Long> mapIds) {
		if (mapIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Map<CodeMapRow.Status, Long>> byMap = new HashMap<>();
		for (Object[] count : rows.countWorkingRowsByStatus(mapIds)) {
			byMap.computeIfAbsent((Long) count[0], mapId -> new EnumMap<>(CodeMapRow.Status.class))
					.put((CodeMapRow.Status) count[1], (Long) count[2]);
		}
		Map<Long, Counts> counts = new HashMap<>();
		byMap.forEach((mapId, byStatus) -> counts.put(mapId, Counts.of(byStatus)));
		return counts;
	}

	/**
	 * The chosen concepts as the release has them. Only identifiers are accepted from the browser,
	 * so a person cannot attach a made-up term to a concept.
	 */
	private Map<Concept, Relationship> conceptsOf(List<RowUpdate.Target> targets, String branch) {
		Map<String, Relationship> relationships = new LinkedHashMap<>();
		targets.forEach(target -> relationships.putIfAbsent(target.conceptId(), target.relationship()));
		if (relationships.isEmpty()) {
			return Map.of();
		}
		Map<String, Concept> active = new HashMap<>();
		snowstorm.activeConcepts(List.copyOf(relationships.keySet()), branch).items()
				.forEach(concept -> active.put(concept.conceptId(), concept));
		Map<Concept, Relationship> chosen = new LinkedHashMap<>();
		relationships.forEach((conceptId, relationship) -> {
			Concept concept = active.get(conceptId);
			if (concept == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						conceptId + " is not an active concept in this release.");
			}
			chosen.put(concept, relationship);
		});
		return chosen;
	}

	private static void requireNoRepeats(List<SourceRow> sent, Set<String> existing) {
		Set<String> seen = new HashSet<>(existing);
		List<String> repeated = new ArrayList<>();
		sent.forEach(row -> {
			if (!seen.add(row.code())) {
				repeated.add(row.code());
			}
		});
		if (!repeated.isEmpty()) {
			List<String> named = repeated.stream().distinct().limit(DUPLICATES_NAMED).toList();
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each code can be in a map once. Repeated: "
					+ String.join(", ", named) + (repeated.size() > named.size() ? " and others." : "."));
		}
	}

	/** One statement for all the rows: a map may have thousands. */
	private void insertRows(long mapId, List<SourceRow> sent, int firstPosition) {
		List<Object[]> values = new ArrayList<>();
		for (int index = 0; index < sent.size(); index++) {
			values.add(new Object[] { mapId, CodeMapRow.WORKING, sent.get(index).code(), sent.get(index).term(),
					CodeMapRow.Status.UNMAPPED.name(), firstPosition + index });
		}
		jdbc.batchUpdate("""
				INSERT INTO code_map_row (map_id, version_number, source_code, source_term, status, position)
				VALUES (?, ?, ?, ?, ?, ?)
				""", values);
	}

	/** The branch of the newest release of a code system, or its live branch if it has no release. */
	private String releaseBranchOf(String codeSystem) {
		CodeSystem found = codeSystem(codeSystem);
		return found.latestVersionBranch() != null ? found.latestVersionBranch() : found.branchPath();
	}

	/** The branch of a chosen release, which must be one Snowstorm lists for the code system. */
	private String releaseBranchOf(String codeSystem, String version) {
		CodeSystem found = codeSystem(codeSystem);
		return snowstorm.codeSystemVersions(found.shortName()).stream()
				.filter(candidate -> candidate.version().equals(version))
				.map(CodeSystemVersion::branchPath)
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						codeSystem + " has no release " + version + "."));
	}

	private CodeSystem codeSystem(String shortName) {
		return snowstorm.codeSystems().stream()
				.filter(candidate -> candidate.shortName().equals(shortName))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Snowstorm has no code system called " + shortName + "."));
	}
}
