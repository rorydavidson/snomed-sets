package org.snomed.valuesets.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.snomed.valuesets.map.CodeMapTarget.Relationship;
import org.snomed.valuesets.map.MapDefinition.SourceRow;
import org.snomed.valuesets.map.MapView.Counts;
import org.snomed.valuesets.map.MapView.RowView;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.CodeSystemVersion;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * All rules about who may see or change a map live here. Maps are private, so every call is for
 * the owner; anyone else gets a 404 as if the map did not exist.
 *
 * A map to SNOMED CT has the local codes as its rows. A map from SNOMED CT has concepts as its
 * rows, and keeps the local codes as a separate list that targets are chosen from.
 *
 * Calls to Snowstorm are made outside database transactions, so a slow Snowstorm does not hold a
 * database connection.
 */
@Service
public class CodeMapService {

	public static final int MAX_ROWS = 5000;
	public static final int MAX_TARGETS = 10;
	private static final int NAMED_IN_ERRORS = 10;
	private static final int SNOWSTORM_BATCH_SIZE = 100;

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
		List<Long> ids = owned.stream().map(CodeMap::getId).toList();
		Map<Long, Counts> counts = countsOf(ids);
		Map<Long, Integer> localCodes = localCodeCounts(ids);
		return owned.stream().map(map -> MapView.of(map, counts.getOrDefault(map.getId(), Counts.NONE),
				localCodes.getOrDefault(map.getId(), 0))).toList();
	}

	public MapView get(long id, CurrentUser user) {
		return view(owned(id, user));
	}

	public MapView create(MapDefinition definition, CurrentUser user) {
		boolean fromSnomed = definition.direction() == CodeMap.Direction.FROM_SNOMED;
		requireNoRepeats(definition.rows(), Set.of());
		String branch = definition.codeSystemVersion() == null
				? releaseBranchOf(definition.codeSystem())
				: releaseBranchOf(definition.codeSystem(), definition.codeSystemVersion());
		List<MapCode> concepts = fromSnomed ? activeConcepts(definition.conceptIds(), branch) : List.of();
		CodeMap created = transaction.execute(status -> {
			CodeMap map = maps.save(new CodeMap(definition.direction(), definition.codeSystem(), branch,
					definition.details(), user.subject(), user.name()));
			if (fromSnomed) {
				insertLocalCodes(map.getId(), definition.rows(), 0);
				insertRows(map.getId(), concepts, 0);
			}
			else {
				insertRows(map.getId(), asRows(definition.rows()), 0);
			}
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

	/** The local codes targets are chosen from, for a map from SNOMED CT. Empty for a map to it. */
	public List<SourceRow> localCodes(long id, CurrentUser user) {
		return localCodes(owned(id, user));
	}

	/**
	 * Adds local codes: more rows to map in a map to SNOMED CT, more codes to choose from in a map
	 * from it. Codes the map already has are refused, not merged.
	 */
	public MapView addLocalCodes(long id, MapDefinition.MoreRows more, CurrentUser user) {
		CodeMap map = owned(id, user);
		return transaction.execute(status -> {
			Set<String> existing = map.isFromSnomed()
					? localCodes(map).stream().map(SourceRow::code).collect(HashSet::new, Set::add, Set::addAll)
					: new HashSet<>(rows.findWorkingCodes(map.getId()));
			requireRoom(existing.size(), more.rows().size(), "codes");
			requireNoRepeats(more.rows(), existing);
			if (map.isFromSnomed()) {
				insertLocalCodes(map.getId(), more.rows(), existing.size());
				// A concept that matched nothing before may match one of the new codes, so it is looked at again.
				jdbc.update("""
						UPDATE code_map_row SET term_checked = FALSE
						WHERE map_id = ? AND version_number = ? AND status = ?
						""", map.getId(), CodeMapRow.WORKING, CodeMapRow.Status.UNMAPPED.name());
			}
			else {
				insertRows(map.getId(), asRows(more.rows()), rows.findLastWorkingPosition(map.getId()) + 1);
			}
			map.touch();
			return view(maps.save(map));
		});
	}

	/** Adds concepts to map, to a map from SNOMED CT. Concepts it already has are left as they are. */
	public MapView addConcepts(long id, MapDefinition.MoreConcepts more, CurrentUser user) {
		CodeMap map = owned(id, user);
		if (!map.isFromSnomed()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Concepts are added to a map from SNOMED CT. This map is to SNOMED CT: add local codes instead.");
		}
		List<MapCode> concepts = activeConcepts(more.conceptIds(), map.getBranch());
		return transaction.execute(status -> {
			Set<String> existing = new HashSet<>(rows.findWorkingCodes(map.getId()));
			List<MapCode> added = concepts.stream().filter(concept -> !existing.contains(concept.code())).toList();
			requireRoom(existing.size(), added.size(), "concepts");
			insertRows(map.getId(), added, rows.findLastWorkingPosition(map.getId()) + 1);
			map.touch();
			return view(maps.save(map));
		});
	}

	/** Sets one row's targets, or records that it has no map. */
	public RowView updateRow(long id, long rowId, RowUpdate update, CurrentUser user) {
		CodeMap map = owned(id, user);
		Map<MapCode, Relationship> chosen = update.noMap() ? Map.of() : targetsOf(update.targets(), map);
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

	/** The working list of local codes of a map from SNOMED CT, in the order they were given. */
	List<SourceRow> localCodes(CodeMap map) {
		return jdbc.query("""
				SELECT code, term FROM code_map_local_code
				WHERE map_id = ? AND version_number = ?
				ORDER BY position
				""", (result, index) -> new SourceRow(result.getString("code"), result.getString("term")),
				map.getId(), CodeMapRow.WORKING);
	}

	private CodeMapRow workingRow(CodeMap map, long rowId) {
		return rows.findByIdAndMapIdAndVersionNumber(rowId, map.getId(), CodeMapRow.WORKING)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Row not found"));
	}

	private MapView view(CodeMap map) {
		List<Long> id = List.of(map.getId());
		return MapView.of(map, countsOf(id).getOrDefault(map.getId(), Counts.NONE),
				localCodeCounts(id).getOrDefault(map.getId(), 0));
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

	private Map<Long, Integer> localCodeCounts(Collection<Long> mapIds) {
		if (mapIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new HashMap<>();
		List<Object> arguments = new ArrayList<>(mapIds);
		arguments.add(CodeMapRow.WORKING);
		jdbc.query("SELECT map_id, COUNT(*) AS codes FROM code_map_local_code WHERE map_id IN ("
				+ String.join(", ", Collections.nCopies(mapIds.size(), "?")) + ") AND version_number = ? GROUP BY map_id",
				result -> {
					counts.put(result.getLong("map_id"), result.getInt("codes"));
				}, arguments.toArray());
		return counts;
	}

	/** The targets as the map's other side has them. Only codes come from the browser, never terms. */
	private Map<MapCode, Relationship> targetsOf(List<RowUpdate.Target> targets, CodeMap map) {
		// LinkedHashMap, so the targets keep the order they were sent in.
		Map<String, Relationship> relationships = new LinkedHashMap<>();
		targets.forEach(target -> relationships.putIfAbsent(target.code(), target.relationship()));
		if (relationships.isEmpty()) {
			return Map.of();
		}
		Map<String, MapCode> known = new HashMap<>();
		if (map.isFromSnomed()) {
			localCodes(map).forEach(local -> known.put(local.code(), MapCode.local(local.code(), local.term())));
		}
		else {
			activeConceptsFound(relationships.keySet(), map.getBranch()).forEach(concept -> known.put(concept.code(), concept));
		}
		Map<MapCode, Relationship> chosen = new LinkedHashMap<>();
		relationships.forEach((code, relationship) -> {
			MapCode target = known.get(code);
			if (target == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, map.isFromSnomed()
						? code + " is not in this map's list of local codes."
						: code + " is not an active concept in this release.");
			}
			chosen.put(target, relationship);
		});
		return chosen;
	}

	/** The given concepts as the release has them, in the order given; a 400 if any is not active there. */
	private List<MapCode> activeConcepts(List<String> conceptIds, String branch) {
		Set<String> wanted = new LinkedHashSet<>(conceptIds);
		Map<String, MapCode> found = new HashMap<>();
		activeConceptsFound(wanted, branch).forEach(concept -> found.put(concept.code(), concept));
		List<String> missing = wanted.stream().filter(conceptId -> !found.containsKey(conceptId)).toList();
		if (!missing.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not active concepts in this release: "
					+ String.join(", ", missing.stream().limit(NAMED_IN_ERRORS).toList())
					+ (missing.size() > NAMED_IN_ERRORS ? " and others." : "."));
		}
		return wanted.stream().map(found::get).toList();
	}

	/** Those of the given identifiers that are active concepts on the branch. Others are simply not returned. */
	private List<MapCode> activeConceptsFound(Collection<String> conceptIds, String branch) {
		// Anything else would be sent to Snowstorm as part of an address.
		List<String> ids = conceptIds.stream().filter(id -> id.matches(ValueSetDefinition.CONCEPT_ID_REGEX)).toList();
		List<MapCode> found = new ArrayList<>();
		for (int start = 0; start < ids.size(); start += SNOWSTORM_BATCH_SIZE) {
			List<String> batch = ids.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, ids.size()));
			snowstorm.activeConcepts(batch, branch).items().forEach(concept -> found.add(MapCode.of(concept)));
		}
		return found;
	}

	private static void requireRoom(int existing, int added, String what) {
		if (existing + added > MAX_ROWS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A map can hold at most " + MAX_ROWS + " " + what + ". This one has " + existing + ".");
		}
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
			List<String> named = repeated.stream().distinct().limit(NAMED_IN_ERRORS).toList();
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each code can be in a map once. Repeated: "
					+ String.join(", ", named) + (repeated.size() > named.size() ? " and others." : "."));
		}
	}

	private static List<MapCode> asRows(List<SourceRow> local) {
		return local.stream().map(row -> MapCode.local(row.code(), row.term())).toList();
	}

	/** One statement for all the rows: a map may have thousands. */
	private void insertRows(long mapId, List<MapCode> sources, int firstPosition) {
		List<Object[]> values = new ArrayList<>();
		for (int index = 0; index < sources.size(); index++) {
			MapCode source = sources.get(index);
			values.add(new Object[] { mapId, CodeMapRow.WORKING, source.code(), source.term(), source.fullySpecifiedName(),
					CodeMapRow.Status.UNMAPPED.name(), firstPosition + index });
		}
		jdbc.batchUpdate("""
				INSERT INTO code_map_row (map_id, version_number, source_code, source_term, source_fsn, status, position)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", values);
	}

	private void insertLocalCodes(long mapId, List<SourceRow> local, int firstPosition) {
		List<Object[]> values = new ArrayList<>();
		for (int index = 0; index < local.size(); index++) {
			values.add(new Object[] { mapId, CodeMapRow.WORKING, local.get(index).code(), local.get(index).term(),
					firstPosition + index });
		}
		jdbc.batchUpdate("""
				INSERT INTO code_map_local_code (map_id, version_number, code, term, position)
				VALUES (?, ?, ?, ?, ?)
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
