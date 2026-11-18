package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Finds the value sets that contain concepts matching some words, or a concept identifier.
 *
 * Only stored concept lists are searched, as Snowstorm is not asked: published versions, and the
 * owner's own hand-picked lists. Each set is searched in the list the person would be shown.
 */
@Service
public class ConceptSearchService {

	public static final int MIN_LENGTH = 3;
	public static final int MAX_LENGTH = 100;
	private static final int MAX_WORDS = 6;
	private static final int EXAMPLES_PER_SET = 3;
	/** A search this broad is cut short rather than read in full. */
	private static final int MAX_ROWS = 20_000;

	private final ValueSetService valueSets;
	private final JdbcTemplate jdbc;

	public ConceptSearchService(ValueSetService valueSets, JdbcTemplate jdbc) {
		this.valueSets = valueSets;
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

	public List<ConceptSearchHit> search(String text, CurrentUser user) {
		String query = checked(text);
		Map<Long, Integer> lists = valueSets.listsShownTo(user);
		if (lists.isEmpty()) {
			return List.of();
		}

		List<Object> args = new ArrayList<>();
		String listCondition = lists.entrySet().stream()
				.map(entry -> {
					args.add(entry.getKey());
					args.add(entry.getValue());
					return "(value_set_id = ? AND version_number = ?)";
				})
				.collect(Collectors.joining(" OR ", "(", ")"));
		boolean byIdentifier = query.matches(ValueSetDefinition.CONCEPT_ID_REGEX);
		String match = byIdentifier ? "concept_id = ?" : wordsCondition(query, args);
		if (byIdentifier) {
			args.add(query);
		}
		args.add(MAX_ROWS);

		Map<Long, Hits> found = new LinkedHashMap<>();
		jdbc.query("SELECT value_set_id, concept_id, preferred_term FROM value_set_member WHERE " + listCondition
				+ " AND " + match + " ORDER BY value_set_id, preferred_term LIMIT ?", row -> {
					Hits hits = found.computeIfAbsent(row.getLong("value_set_id"), id -> new Hits());
					hits.count++;
					if (hits.examples.size() < EXAMPLES_PER_SET) {
						hits.examples.add(new ConceptSearchHit.Match(row.getString("concept_id"), row.getString("preferred_term")));
					}
				}, args.toArray());

		// In the order the library lists the sets, newest change first.
		return lists.keySet().stream()
				.filter(found::containsKey)
				.map(id -> new ConceptSearchHit(id, found.get(id).count, List.copyOf(found.get(id).examples)))
				.toList();
	}

	/** Every word must be in the preferred term, or every word in the fully specified name. */
	private static String wordsCondition(String query, List<Object> args) {
		List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split(" "))
				.distinct().limit(MAX_WORDS).toList();
		String inTerm = words.stream().map(word -> "LOWER(preferred_term) LIKE ? ESCAPE '!'").collect(Collectors.joining(" AND "));
		String inFsn = words.stream().map(word -> "LOWER(fully_specified_name) LIKE ? ESCAPE '!'").collect(Collectors.joining(" AND "));
		words.forEach(word -> args.add(likeAnywhere(word)));
		words.forEach(word -> args.add(likeAnywhere(word)));
		return "((" + inTerm + ") OR (" + inFsn + "))";
	}

	/** Typed % and _ are matched as themselves, not as wildcards. */
	private static String likeAnywhere(String word) {
		return "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}

	private static final class Hits {
		int count;
		final List<ConceptSearchHit.Match> examples = new ArrayList<>();
	}
}
