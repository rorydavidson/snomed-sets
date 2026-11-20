package org.snomed.valuesets.map;

import java.text.Normalizer;
import java.util.ArrayList;
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
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Looks for each row's term among SNOMED CT descriptions, synonyms included, and stores the
 * concepts whose description is the same words as suggestions. Nothing is accepted here: the same
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
		List<String> languages = languagesOf(map.getBranch());

		Map<Long, Future<List<Concept>>> pending = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_SEARCHES, Thread.ofVirtual().factory())) {
			unmapped.forEach(row -> pending.put(row.getId(),
					executor.submit(() -> conceptsNamed(row.getSourceTerm(), map.getBranch(), languages))));
		}
		Map<Long, List<Concept>> matches = new LinkedHashMap<>();
		pending.forEach((rowId, future) -> matches.put(rowId, resultOf(future)));

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

	/** The active concepts with a description that is the term, apart from case and spacing. */
	private List<Concept> conceptsNamed(String term, String branch, List<String> languages) {
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
		Map<String, Concept> found = new LinkedHashMap<>();
		descriptions.stream()
				.filter(description -> normalised(description.term()).equals(wanted))
				.forEach(description -> found.putIfAbsent(description.concept().conceptId(), description.concept()));
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
