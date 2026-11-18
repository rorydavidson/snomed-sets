package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetService.ShownList;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Finds the value sets whose concept lists hold concepts matching a search.
 *
 * Words are matched by Snowstorm against every active description of each concept, in the
 * languages of the edition, by the start of each word and in any order, so a synonym or part of
 * a word is enough. Snowstorm is asked once for each release the lists were built on, and the
 * concepts it finds are then looked for in the stored lists. A SNOMED CT ID is looked up in the
 * lists directly.
 *
 * Each set is searched in the list the person would be shown: the version in use, or the owner's
 * own working list of a hand-picked set. An ECL set that has never been published has no stored
 * list, so it is not found.
 */
@Service
public class ConceptSearchService {

	public static final int MIN_LENGTH = 3;
	public static final int MAX_LENGTH = 100;
	/** The most matching concepts asked of Snowstorm for one release; its own most for one page. */
	static final int MAX_CONCEPTS_PER_BRANCH = 10_000;
	private static final int MAX_WORDS = 6;
	private static final int EXAMPLES_PER_SET = 3;
	/** Concept identifiers per query, to keep each IN list a reasonable size. */
	private static final int ID_CHUNK = 1_000;

	private static final Logger log = LoggerFactory.getLogger(ConceptSearchService.class);

	private final ValueSetService valueSets;
	private final SnowstormClient snowstorm;
	private final JdbcTemplate jdbc;

	public ConceptSearchService(ValueSetService valueSets, SnowstormClient snowstorm, JdbcTemplate jdbc) {
		this.valueSets = valueSets;
		this.snowstorm = snowstorm;
		this.jdbc = jdbc;
	}

	/** The search tidied up, or a 400 if it is too short or too long to run. */
	public static String checked(String text) {
		String query = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		if (query.length() < MIN_LENGTH || query.length() > MAX_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Search for between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
		}
		return query;
	}

	public ConceptSearchResult search(String text, CurrentUser user) {
		String query = checked(text);
		Map<Long, ShownList> lists = valueSets.listsShownTo(user);
		if (lists.isEmpty()) {
			return new ConceptSearchResult(List.of(), false, false);
		}
		if (query.matches(ValueSetDefinition.CONCEPT_ID_REGEX)) {
			return new ConceptSearchResult(hits(lists, find(lists, "concept_id = ?", List.of(query))), false, false);
		}
		try {
			return searchDescriptions(query, lists);
		} catch (SnowstormException e) {
			log.warn("Snowstorm could not search descriptions, so stored terms are searched instead: {}", e.getMessage());
			return new ConceptSearchResult(hits(lists, findInStoredTerms(lists, query)), false, true);
		}
	}

	private ConceptSearchResult searchDescriptions(String query, Map<Long, ShownList> lists) {
		Map<String, Map<Long, ShownList>> byBranch = lists.entrySet().stream()
				.collect(Collectors.groupingBy(entry -> entry.getValue().branch(), LinkedHashMap::new,
						Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)));
		Map<Long, Hits> found = new LinkedHashMap<>();
		boolean truncated = false;
		for (var branch : byBranch.entrySet()) {
			ExpansionPage matches = snowstorm.conceptsWithDescriptionMatching(query, branch.getKey(),
					languagesOf(branch.getKey()), MAX_CONCEPTS_PER_BRANCH);
			truncated |= matches.total() > matches.items().size();
			List<String> conceptIds = matches.items().stream().map(ExpansionPage.Concept::conceptId).toList();
			for (int from = 0; from < conceptIds.size(); from += ID_CHUNK) {
				List<String> chunk = conceptIds.subList(from, Math.min(conceptIds.size(), from + ID_CHUNK));
				String in = chunk.stream().map(id -> "?").collect(Collectors.joining(", ", "concept_id IN (", ")"));
				find(branch.getValue(), in, chunk).forEach((id, hits) -> found.merge(id, hits, Hits::add));
			}
		}
		return new ConceptSearchResult(hits(lists, found), truncated, false);
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

	/** When Snowstorm cannot be asked: every word in the stored preferred term, or in the stored FSN. */
	private Map<Long, Hits> findInStoredTerms(Map<Long, ShownList> lists, String query) {
		List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split(" ")).distinct().limit(MAX_WORDS).toList();
		String inTerm = words.stream().map(word -> "LOWER(preferred_term) LIKE ? ESCAPE '!'").collect(Collectors.joining(" AND "));
		String inFsn = words.stream().map(word -> "LOWER(fully_specified_name) LIKE ? ESCAPE '!'").collect(Collectors.joining(" AND "));
		List<Object> args = new ArrayList<>();
		words.forEach(word -> args.add(likeAnywhere(word)));
		words.forEach(word -> args.add(likeAnywhere(word)));
		return find(lists, "((" + inTerm + ") OR (" + inFsn + "))", args);
	}

	/** Matching members of the given lists, grouped by value set. */
	private Map<Long, Hits> find(Map<Long, ShownList> lists, String condition, Collection<?> conditionArgs) {
		List<Object> args = new ArrayList<>();
		String inLists = lists.entrySet().stream()
				.map(entry -> {
					args.add(entry.getKey());
					args.add(entry.getValue().version());
					return "(value_set_id = ? AND version_number = ?)";
				})
				.collect(Collectors.joining(" OR ", "(", ")"));
		args.addAll(conditionArgs);
		Map<Long, Hits> found = new LinkedHashMap<>();
		jdbc.query("SELECT value_set_id, concept_id, preferred_term FROM value_set_member WHERE " + inLists
				+ " AND " + condition + " ORDER BY value_set_id, preferred_term", row -> {
					found.computeIfAbsent(row.getLong("value_set_id"), id -> new Hits())
							.add(row.getString("concept_id"), row.getString("preferred_term"));
				}, args.toArray());
		return found;
	}

	/** In the order the library lists the sets, newest change first. */
	private static List<ConceptSearchHit> hits(Map<Long, ShownList> lists, Map<Long, Hits> found) {
		return lists.keySet().stream()
				.filter(found::containsKey)
				.map(id -> found.get(id).toHit(id))
				.toList();
	}

	/** Typed % and _ are matched as themselves, not as wildcards. */
	private static String likeAnywhere(String word) {
		return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}

	private static final class Hits {
		private final Map<String, String> termsById = new LinkedHashMap<>();

		void add(String conceptId, String term) {
			termsById.putIfAbsent(conceptId, term);
		}

		Hits add(Hits other) {
			other.termsById.forEach(this::add);
			return this;
		}

		ConceptSearchHit toHit(long valueSetId) {
			List<ConceptSearchHit.Match> examples = termsById.entrySet().stream()
					.sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
					.limit(EXAMPLES_PER_SET)
					.map(entry -> new ConceptSearchHit.Match(entry.getKey(), entry.getValue()))
					.toList();
			return new ConceptSearchHit(valueSetId, termsById.size(), examples);
		}
	}
}
