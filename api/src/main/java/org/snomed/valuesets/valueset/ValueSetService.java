package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetView.VersionView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ValueSetService {

	private final ValueSetRepository valueSets;
	private final ValueSetVersionRepository versions;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;

	public ValueSetService(ValueSetRepository valueSets, ValueSetVersionRepository versions,
			SnowstormClient snowstorm, TransactionTemplate transaction) {
		this.valueSets = valueSets;
		this.versions = versions;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
	}

	public List<ValueSetView> list(CurrentUser user) {
		List<ValueSet> visible = valueSets.findAllVisibleTo(user.subject());
		Map<Long, ValueSetVersion> latestById = versions
				.findLatestOfEach(visible.stream().map(ValueSet::getId).toList()).stream()
				.collect(Collectors.toMap(ValueSetVersion::getValueSetId, Function.identity()));
		return visible.stream()
				.map(valueSet -> ValueSetView.of(valueSet, latestById.get(valueSet.getId()), user))
				.toList();
	}

	public ValueSetView get(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		return ValueSetView.of(valueSet, latestVersionOf(valueSet), user);
	}

	public ValueSetView create(ValueSetDefinition definition, CurrentUser user) {
		ValueSet valueSet = new ValueSet(definition.name(), definition.description(), definition.ecl(),
				definition.branch(), definition.visibility(), user.subject(), user.name());
		return ValueSetView.of(valueSets.save(valueSet), null, user);
	}

	public ValueSetView update(long id, ValueSetDefinition definition, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		valueSet.update(definition.name(), definition.description(), definition.ecl(), definition.branch(),
				definition.visibility());
		return ValueSetView.of(valueSets.save(valueSet), latestVersionOf(valueSet), user);
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

	/** Expands what the caller sees as the current definition: see {@link ValueSetView#of}. */
	public ExpansionPage expansion(long id, CurrentUser user, int offset, int limit) {
		ValueSetView view = get(id, user);
		return snowstorm.expand(view.ecl(), view.branch(), offset, limit);
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
		return snowstorm.expand(version.getEcl(), version.getBranch(), offset, limit);
	}

	public VersionView publish(long id, String note, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		ValueSetVersion latest = latestVersionOf(valueSet);
		if (latest != null && latest.hasSameDefinitionAs(valueSet)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Nothing has changed since version " + latest.getVersionNumber() + ".");
		}
		// Only a definition that Snowstorm can expand may be published. This also gives the concept count.
		// It runs before the transaction so a slow Snowstorm does not hold a database connection.
		long conceptCount = snowstorm.expand(valueSet.getEcl(), valueSet.getBranch(), 0, 1).total();
		int number = latest == null ? 1 : latest.getVersionNumber() + 1;

		ValueSetVersion published = transaction.execute(status -> {
			valueSet.markPublished();
			valueSets.save(valueSet);
			return versions.save(new ValueSetVersion(valueSet, number, conceptCount, note));
		});
		return VersionView.of(published);
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
