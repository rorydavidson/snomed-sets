package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetView.VersionView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * All rules about who may see or change a value set live here.
 *
 * Calls to Snowstorm are made outside database transactions, so a slow Snowstorm
 * does not hold a database connection.
 */
@Service
public class ValueSetService {

	private static final int SNOWSTORM_BATCH_SIZE = 100;

	private final ValueSetRepository valueSets;
	private final ValueSetVersionRepository versions;
	private final ValueSetMemberRepository members;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;

	public ValueSetService(ValueSetRepository valueSets, ValueSetVersionRepository versions,
			ValueSetMemberRepository members, SnowstormClient snowstorm, TransactionTemplate transaction) {
		this.valueSets = valueSets;
		this.versions = versions;
		this.members = members;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
	}

	public List<ValueSetView> list(CurrentUser user) {
		List<ValueSet> visible = valueSets.findAllVisibleTo(user.subject());
		Map<Long, ValueSetVersion> latestById = versions
				.findLatestOfEach(visible.stream().map(ValueSet::getId).toList()).stream()
				.collect(Collectors.toMap(ValueSetVersion::getValueSetId, Function.identity()));
		return visible.stream()
				.map(valueSet -> view(valueSet, latestById.get(valueSet.getId()), user))
				.toList();
	}

	public ValueSetView get(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		return view(valueSet, latestVersionOf(valueSet), user);
	}

	public ValueSetView create(ValueSetDefinition definition, CurrentUser user) {
		requireEclIfNeeded(definition);
		boolean manual = definition.type() == ValueSet.DefinitionType.MANUAL;
		List<Concept> concepts = manual ? resolveConcepts(definition, List.of()) : List.of();

		ValueSet created = transaction.execute(status -> {
			ValueSet valueSet = valueSets.save(new ValueSet(definition.type(), definition.name(),
					definition.description(), manual ? "" : definition.ecl(), definition.branch(),
					definition.visibility(), user.subject(), user.name()));
			saveWorkingList(valueSet, concepts);
			return valueSet;
		});
		return view(created, null, user);
	}

	public ValueSetView update(long id, ValueSetDefinition definition, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		if (definition.type() != valueSet.getDefinitionType()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A value set cannot be changed between an ECL definition and a hand-picked list.");
		}
		requireEclIfNeeded(definition);
		List<Concept> concepts = valueSet.isManual() ? resolveConcepts(definition, workingList(valueSet)) : List.of();

		transaction.executeWithoutResult(status -> {
			valueSet.update(definition.name(), definition.description(), valueSet.isManual() ? "" : definition.ecl(),
					definition.branch(), definition.visibility());
			valueSets.save(valueSet);
			if (valueSet.isManual()) {
				members.deleteWorkingList(valueSet.getId());
				saveWorkingList(valueSet, concepts);
			}
		});
		return view(valueSet, latestVersionOf(valueSet), user);
	}

	public void delete(long id, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		// Other people may be relying on a published, shared set, so it has to be withdrawn first.
		if (valueSet.getStatus() == ValueSet.Status.PUBLISHED
				&& valueSet.getVisibility() == ValueSet.Visibility.COMMUNITY) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A published value set that is shared with the community cannot be deleted. Stop sharing it first.");
		}
		valueSets.delete(valueSet);
	}

	/** The hand-picked list as it is being edited. Only the owner edits, so only the owner may read it. */
	public List<Concept> workingMembers(long id, CurrentUser user) {
		return workingList(findOwned(id, user));
	}

	/**
	 * Expands what the caller sees as the current content. The owner sees what they are
	 * working on. Everyone else sees the latest published version, so a set they rely on
	 * does not change until its owner publishes again.
	 */
	public ExpansionPage expansion(long id, CurrentUser user, int offset, int limit) {
		ValueSet valueSet = findVisible(id, user);
		ValueSetVersion latest = latestVersionOf(valueSet);
		boolean showPublished = latest != null && !valueSet.isOwnedBy(user.subject());
		if (valueSet.isManual()) {
			int versionNumber = showPublished ? latest.getVersionNumber() : ValueSetMember.WORKING_LIST;
			return memberPage(valueSet, versionNumber, offset, limit);
		}
		return showPublished
				? snowstorm.expand(latest.getEcl(), latest.getBranch(), offset, limit)
				: snowstorm.expand(valueSet.getEcl(), valueSet.getBranch(), offset, limit);
	}

	public List<VersionView> versions(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		return versions.findByValueSetIdOrderByVersionNumberDesc(valueSet.getId()).stream()
				.map(VersionView::of)
				.toList();
	}

	public ExpansionPage versionExpansion(long id, int versionNumber, CurrentUser user, int offset, int limit) {
		ValueSet valueSet = findVisible(id, user);
		ValueSetVersion version = versions.findByValueSetIdAndVersionNumber(valueSet.getId(), versionNumber)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
		return valueSet.isManual()
				? memberPage(valueSet, version.getVersionNumber(), offset, limit)
				: snowstorm.expand(version.getEcl(), version.getBranch(), offset, limit);
	}

	public VersionView publish(long id, String note, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		ValueSetVersion latest = latestVersionOf(valueSet);
		if (latest != null && !differsFrom(valueSet, latest)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Nothing has changed since version " + latest.getVersionNumber() + ".");
		}
		long conceptCount = countForPublishing(valueSet);
		int number = latest == null ? 1 : latest.getVersionNumber() + 1;

		ValueSetVersion published = transaction.execute(status -> {
			valueSet.markPublished();
			valueSets.save(valueSet);
			if (valueSet.isManual()) {
				members.copyWorkingListToVersion(valueSet.getId(), number);
			}
			return versions.save(new ValueSetVersion(valueSet, number, conceptCount, note));
		});
		return VersionView.of(published);
	}

	private long countForPublishing(ValueSet valueSet) {
		if (valueSet.isManual()) {
			long count = members.countByValueSetIdAndVersionNumber(valueSet.getId(), ValueSetMember.WORKING_LIST);
			if (count == 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Add at least one concept before publishing.");
			}
			return count;
		}
		// Only an ECL definition that Snowstorm can expand may be published.
		return snowstorm.expand(valueSet.getEcl(), valueSet.getBranch(), 0, 1).total();
	}

	private ValueSetView view(ValueSet valueSet, ValueSetVersion latest, CurrentUser user) {
		// Working this out costs a query for a hand-picked set, and only the owner is told.
		boolean unpublishedChanges = latest != null && valueSet.isOwnedBy(user.subject())
				&& differsFrom(valueSet, latest);
		return ValueSetView.of(valueSet, latest, unpublishedChanges, user);
	}

	private boolean differsFrom(ValueSet valueSet, ValueSetVersion version) {
		if (valueSet.isManual()) {
			return !members.findConceptIds(valueSet.getId(), ValueSetMember.WORKING_LIST)
					.equals(members.findConceptIds(valueSet.getId(), version.getVersionNumber()));
		}
		return !version.getEcl().equals(valueSet.getEcl()) || !version.getBranch().equals(valueSet.getBranch());
	}

	private static void requireEclIfNeeded(ValueSetDefinition definition) {
		if (definition.type() == ValueSet.DefinitionType.ECL && definition.ecl().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An ECL expression is required.");
		}
	}

	/**
	 * Turns the requested concept identifiers into concepts with terms. Concepts already
	 * in the list keep their terms; new ones are looked up in Snowstorm, which also
	 * confirms that they exist and are active.
	 */
	private List<Concept> resolveConcepts(ValueSetDefinition definition, List<Concept> alreadyInList) {
		Map<String, Concept> known = alreadyInList.stream()
				.collect(Collectors.toMap(Concept::conceptId, Function.identity()));
		Set<String> requested = new LinkedHashSet<>(definition.conceptIds());
		List<String> unknown = requested.stream().filter(conceptId -> !known.containsKey(conceptId)).toList();

		for (int start = 0; start < unknown.size(); start += SNOWSTORM_BATCH_SIZE) {
			List<String> batch = unknown.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, unknown.size()));
			snowstorm.expand(String.join(" OR ", batch), definition.branch(), 0, SNOWSTORM_BATCH_SIZE).items()
					.forEach(concept -> known.put(concept.conceptId(), concept));
		}

		List<String> notFound = requested.stream().filter(conceptId -> !known.containsKey(conceptId)).toList();
		if (!notFound.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"These concepts are not active concepts on " + definition.branch() + ": "
							+ String.join(", ", notFound.subList(0, Math.min(5, notFound.size()))));
		}
		return requested.stream().map(known::get).toList();
	}

	private void saveWorkingList(ValueSet valueSet, List<Concept> concepts) {
		List<ValueSetMember> rows = new ArrayList<>();
		concepts.forEach(concept -> rows.add(new ValueSetMember(valueSet.getId(), concept)));
		members.saveAll(rows);
	}

	private List<Concept> workingList(ValueSet valueSet) {
		return members.findByValueSetIdAndVersionNumberOrderByPreferredTermAscConceptIdAsc(valueSet.getId(),
				ValueSetMember.WORKING_LIST).stream().map(ValueSetMember::toConcept).toList();
	}

	private ExpansionPage memberPage(ValueSet valueSet, int versionNumber, int offset, int limit) {
		List<Concept> concepts = members.findPage(valueSet.getId(), versionNumber, offset, limit).stream()
				.map(ValueSetMember::toConcept).toList();
		long total = members.countByValueSetIdAndVersionNumber(valueSet.getId(), versionNumber);
		return new ExpansionPage(concepts, total, offset, limit);
	}

	private ValueSetVersion latestVersionOf(ValueSet valueSet) {
		return versions.findFirstByValueSetIdOrderByVersionNumberDesc(valueSet.getId()).orElse(null);
	}

	private ValueSet findVisible(long id, CurrentUser user) {
		// A set the caller may not see is reported as missing, so its existence is not revealed.
		return valueSets.findVisibleById(id, user.subject())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Value set not found"));
	}

	/** Only the owner may change, publish or delete a set, even one that everybody can see. */
	private ValueSet findOwned(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		if (!valueSet.isOwnedBy(user.subject())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can change this value set.");
		}
		return valueSet;
	}
}
