package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetService.ShownList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Counts what the community has shared. Worked out on every request, so a set made private or
 * deleted drops out at once; it costs about the same as listing the library.
 */
@Service
public class LibraryStatsService {

	private final ValueSetService valueSets;
	private final JdbcTemplate jdbc;

	public LibraryStatsService(ValueSetService valueSets, JdbcTemplate jdbc) {
		this.valueSets = valueSets;
		this.jdbc = jdbc;
	}

	public LibraryStats stats() {
		// What a visitor may see: published community sets, each in the version in use.
		Map<Long, ShownList> lists = valueSets.listsShownTo(CurrentUser.ANONYMOUS);
		long sets = valueSets.list(CurrentUser.ANONYMOUS).size();
		if (lists.isEmpty()) {
			return new LibraryStats(sets, 0);
		}
		List<Object> args = new ArrayList<>();
		String inLists = lists.entrySet().stream()
				.map(entry -> {
					args.add(entry.getKey());
					args.add(entry.getValue().version());
					return "(value_set_id = ? AND version_number = ?)";
				})
				.collect(Collectors.joining(" OR "));
		Long concepts = jdbc.queryForObject("SELECT COUNT(DISTINCT concept_id) FROM value_set_member WHERE " + inLists,
				Long.class, args.toArray());
		return new LibraryStats(sets, concepts == null ? 0 : concepts);
	}
}
