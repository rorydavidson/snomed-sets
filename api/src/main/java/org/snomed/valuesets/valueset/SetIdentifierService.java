package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks a SNOMED CT identifier given to a value set: it must be a well-formed concept identifier,
 * name an active concept in the set's release, be a kind of reference set, and not identify
 * another value set already.
 */
@Service
public class SetIdentifierService {

	/** 900000000000455006 |Reference set (foundation metadata concept)| */
	static final String REFERENCE_SET = "900000000000455006";

	private final SnowstormClient snowstorm;
	private final JdbcTemplate jdbc;

	public SetIdentifierService(SnowstormClient snowstorm, JdbcTemplate jdbc) {
		this.snowstorm = snowstorm;
		this.jdbc = jdbc;
	}

	/**
	 * @param branch the release the value set is built on
	 * @param valueSetId the set being edited, which may keep its own identifier; null for a new set
	 */
	public SetIdentifier check(String sctid, String branch, Long valueSetId, CurrentUser user) {
		String id = sctid == null ? "" : sctid.strip();
		if (!SctId.hasValidShape(id) || !SctId.hasValidCheckDigit(id)) {
			throw invalid(id + " is not a valid SNOMED CT identifier. Check for a mistyped digit.");
		}
		if (SctId.kindOf(id) != SctId.Kind.CONCEPT) {
			throw invalid(id + " identifies a description or relationship, not a concept.");
		}
		requireUnused(id, valueSetId, user);
		if (!SnowstormClient.isValidBranch(branch)) {
			throw invalid("The release to check the identifier against is not valid.");
		}

		ExpansionPage asReferenceSet;
		try {
			asReferenceSet = snowstorm.expand(id + " AND << " + REFERENCE_SET, branch, 0, 1);
			if (asReferenceSet.items().isEmpty()) {
				boolean exists = !snowstorm.expand(id, branch, 0, 1).items().isEmpty();
				throw invalid(exists
						? id + " is not a reference set. Use a concept under 900000000000455006 |Reference set|."
						: id + " is not an active concept in " + releaseOf(branch) + ".");
			}
		} catch (SnowstormException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"The identifier could not be checked because Snowstorm could not be reached. Try again later.");
		}
		ExpansionPage.Concept concept = asReferenceSet.items().getFirst();
		return new SetIdentifier(concept.conceptId(), concept.fullySpecifiedName(), concept.preferredTerm());
	}

	/** The id of the value set identified by this concept, if any. */
	public Optional<Long> valueSetIdentifiedBy(String sctid) {
		List<Long> ids = jdbc.queryForList("SELECT id FROM value_set WHERE sctid = ?", Long.class, sctid);
		return ids.stream().findFirst();
	}

	/** Another set's name is only given when the person may see that set. */
	private void requireUnused(String sctid, Long valueSetId, CurrentUser user) {
		List<Map<String, Object>> holders = jdbc.queryForList(
				"SELECT id, name, visibility, owner_subject FROM value_set WHERE sctid = ?", sctid);
		for (var holder : holders) {
			long holderId = ((Number) holder.get("id")).longValue();
			if (valueSetId != null && holderId == valueSetId) {
				continue;
			}
			boolean visible = "COMMUNITY".equals(holder.get("visibility"))
					|| (user.subject() != null && user.subject().equals(holder.get("owner_subject")));
			throw new ResponseStatusException(HttpStatus.CONFLICT, visible
					? sctid + " already identifies the value set \"" + holder.get("name") + "\"."
					: sctid + " already identifies another value set.");
		}
	}

	private static String releaseOf(String branch) {
		String release = ValueSet.releaseOf(branch);
		return release == null ? "this code system" : "the " + release + " release";
	}

	private static ResponseStatusException invalid(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
