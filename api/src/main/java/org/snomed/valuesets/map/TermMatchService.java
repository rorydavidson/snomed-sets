package org.snomed.valuesets.map;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.snomed.valuesets.map.MapView.RowView;
import org.snomed.valuesets.snowstorm.DescriptionMatch;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Looks for the same words on both sides of a map and stores what is found as suggestions. In a
 * map to SNOMED CT each row's term is looked for among SNOMED CT descriptions, synonyms included.
 * In a map from SNOMED CT every name of each row's concept is looked for among the local terms. Nothing is accepted here: the same
 * words can name a finding, a disorder and a morphology, so a person decides.
 */
@Service
public class TermMatchService {

	/** The most rows matched in one request. The browser sends a long map a piece at a time. */
	public static final int MAX_ROWS_PER_REQUEST = 100;
	/** Snowstorm has no call for many terms at once, so a few are asked for at a time. */
	private static final int PARALLEL_SEARCHES = 8;
	/** Snowstorm refuses shorter search terms. */
	private static final int SHORTEST_SEARCH = 3;
	/** Exact matches are the shortest descriptions found, so they are in the first few. */
	private static final int DESCRIPTIONS_READ = 50;
	/** How many concepts' names are asked for at once. They go in the address, so not too many. */
	private static final int CONCEPTS_PER_REQUEST = 25;

	private final CodeMapService maps;
	private final CodeMapRowRepository rows;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;

	public TermMatchService(CodeMapService maps, CodeMapRowRepository rows, SnowstormClient snowstorm,
			TransactionTemplate transaction) {
		this.maps = maps;
		this.rows = rows;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
	}

	/**
	 * Matches the terms of the given rows that have no targets yet, and returns those rows as they
	 * are afterwards. Rows the owner has already dealt with are left alone and not returned.
	 */
	public List<RowView> match(long mapId, List<Long> rowIds, CurrentUser user) {
		CodeMap map = maps.owned(mapId, user);
		List<CodeMapRow> unmapped = rows.findByMapIdAndVersionNumberAndIdInOrderByPosition(map.getId(),
				CodeMapRow.WORKING, rowIds).stream().filter(row -> row.getStatus() == CodeMapRow.Status.UNMAPPED).toList();
		Map<Long, List<MapCode>> matches = map.isFromSnomed() ? localCodesNamedLike(unmapped, map) : conceptsNamedLike(unmapped, map);

		return transaction.execute(status -> {
			List<RowView> matched = new ArrayList<>();
			// Read again inside the transaction, in case the owner mapped a row while Snowstorm was searched.
			for (CodeMapRow row : rows.findByMapIdAndVersionNumberAndIdInOrderByPosition(map.getId(), CodeMapRow.WORKING,
					matches.keySet())) {
				row.suggest(matches.get(row.getId()));
				matched.add(RowView.of(rows.save(row)));
			}
			return matched;
		});
	}

	/** For each row of a map to SNOMED CT, the concepts with a description that is the row's term. */
	private Map<Long, List<MapCode>> conceptsNamedLike(List<CodeMapRow> unmapped, CodeMap map) {
		List<String> languages = languagesOf(map.getBranch());
		Map<Long, Future<List<MapCode>>> pending = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_SEARCHES, Thread.ofVirtual().factory())) {
			unmapped.forEach(row -> pending.put(row.getId(),
					executor.submit(() -> conceptsNamed(row.getSourceTerm(), map.getBranch(), languages))));
		}
		Map<Long, List<MapCode>> matches = new LinkedHashMap<>();
		pending.forEach((rowId, future) -> matches.put(rowId, resultOf(future)));
		return matches;
	}

	/**
	 * For each row of a map from SNOMED CT, the local codes whose term is one of the names of the
	 * row's concept: its preferred term, fully specified name or any synonym, in any language.
	 */
	private Map<Long, List<MapCode>> localCodesNamedLike(List<CodeMapRow> unmapped, CodeMap map) {
		Map<String, List<MapCode>> localByTerm = new HashMap<>();
		maps.localCodes(map).stream()
				.filter(local -> !local.term().isBlank())
				.forEach(local -> localByTerm.computeIfAbsent(normalised(local.term()), term -> new ArrayList<>())
						.add(MapCode.local(local.code(), local.term())));

		Map<String, Set<String>> namesByConcept = new HashMap<>();
		List<String> conceptIds = unmapped.stream().map(CodeMapRow::getSourceCode).toList();
		for (int start = 0; start < conceptIds.size(); start += CONCEPTS_PER_REQUEST) {
			snowstorm.termsOf(conceptIds.subList(start, Math.min(start + CONCEPTS_PER_REQUEST, conceptIds.size())),
					map.getBranch()).forEach(name -> namesByConcept
							.computeIfAbsent(name.conceptId(), conceptId -> new LinkedHashSet<>()).add(normalised(name.term())));
		}

		Map<Long, List<MapCode>> matches = new LinkedHashMap<>();
		for (CodeMapRow row : unmapped) {
			Map<String, MapCode> found = new LinkedHashMap<>();
			namesByConcept.getOrDefault(row.getSourceCode(), Set.of()).forEach(name -> localByTerm
					.getOrDefault(name, List.of()).forEach(local -> found.putIfAbsent(local.code(), local)));
			matches.put(row.getId(), found.values().stream().limit(CodeMapService.MAX_TARGETS).toList());
		}
		return matches;
	}

	/** The active concepts with a description that is the term, apart from case and spacing. */
	private List<MapCode> conceptsNamed(String term, String branch, List<String> languages) {
		String wanted = normalised(term);
		if (wanted.length() < SHORTEST_SEARCH) {
			return List.of();
		}
		List<DescriptionMatch> descriptions;
		try {
			descriptions = snowstorm.descriptionsMatching(wanted, branch, languages, DESCRIPTIONS_READ);
		}
		catch (SnowstormException e) {
			// A term Snowstorm cannot search for, such as one that is all punctuation, has no match.
			// Any other failure stops the request, so the rows can be tried again.
			if (e.isCausedByRequest()) {
				return List.of();
			}
			throw e;
		}
		Map<String, MapCode> found = new LinkedHashMap<>();
		descriptions.stream()
				.filter(description -> normalised(description.term()).equals(wanted))
				.forEach(description -> found.putIfAbsent(description.concept().conceptId(), MapCode.of(description.concept())));
		return found.values().stream().limit(CodeMapService.MAX_TARGETS).toList();
	}

	/** Lower case, one space between words, and one form for accented letters. */
	static String normalised(String term) {
		return Normalizer.normalize(term, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	/** Every language the edition holds, so a synonym in any of them is found. English is always included. */
	private List<String> languagesOf(String branch) {
		Set<String> languages = new LinkedHashSet<>();
		Edition edition = snowstorm.editionFor(branch);
		if (edition != null && edition.languages() != null) {
			languages.addAll(edition.languages().keySet());
		}
		languages.add("en");
		return List.copyOf(languages);
	}

	private static <T> T resultOf(Future<T> future) {
		try {
			return future.get();
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof SnowstormException snowstormFailure) {
				throw snowstormFailure;
			}
			throw new IllegalStateException(e.getCause());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SnowstormException("Matching terms was interrupted.", false, e);
		}
	}
}
