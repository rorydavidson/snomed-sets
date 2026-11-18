package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.snomed.valuesets.message.MessagingProperties;
import org.snomed.valuesets.profile.OwnerProfile;
import org.snomed.valuesets.profile.ProfileService;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.CodeSystemVersion;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSetView.VersionView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
	private static final int MAX_ECL_LENGTH = 4000;
	private static final int PUBLISH_PAGE_SIZE = 1000;
	/** Snowstorm does not page past 10,000 results without a different paging method. */
	static final int MAX_PUBLISHED_CONCEPTS = 10_000;

	private final ValueSetRepository valueSets;
	private final ValueSetVersionRepository versions;
	private final ValueSetMemberRepository members;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;
	private final JdbcTemplate jdbc;
	private final ProfileService profiles;
	private final MessagingProperties messaging;
	private final SetIdentifierService identifiers;

	public ValueSetService(ValueSetRepository valueSets, ValueSetVersionRepository versions,
			ValueSetMemberRepository members, SnowstormClient snowstorm, TransactionTemplate transaction,
			JdbcTemplate jdbc, ProfileService profiles, MessagingProperties messaging, SetIdentifierService identifiers) {
		this.identifiers = identifiers;
		this.jdbc = jdbc;
		this.profiles = profiles;
		this.messaging = messaging;
		this.valueSets = valueSets;
		this.versions = versions;
		this.members = members;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
	}

	public List<ValueSetView> list(CurrentUser user) {
		List<ValueSet> visible = user.isSignedIn()
				? valueSets.findAllVisibleTo(user.subject())
				: valueSets.findAllPublished();
		Map<Long, List<ValueSetVersion>> versionsById = versions
				.findByValueSetIdInOrderByVersionNumberDesc(visible.stream().map(ValueSet::getId).toList()).stream()
				.collect(Collectors.groupingBy(ValueSetVersion::getValueSetId));
		// Visitors are not told who made a set, so owners are only looked up for people signed in.
		Map<String, OwnerProfile> owners = user.isSignedIn()
				? profiles.ownerProfiles(visible.stream().map(ValueSet::getOwnerSubject).toList())
				: Map.of();
		return visible.stream()
				.map(valueSet -> view(valueSet, versionInUse(versionsById.getOrDefault(valueSet.getId(), List.of())), user,
						owners.getOrDefault(valueSet.getOwnerSubject(), OwnerProfile.none(valueSet.getOwnerSubject()))))
				.toList();
	}

	/** Tags already in use, to choose from when tagging a value set. */
	public List<String> tags(CurrentUser user) {
		return valueSets.findTagsVisibleTo(user.subject());
	}

	/**
	 * Saves, reporting a clash when someone else gave another set the same identifier in the moment
	 * since it was checked.
	 */
	private static <T> T saveUniquely(Supplier<T> save) {
		try {
			return save.get();
		} catch (DataIntegrityViolationException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Someone else just used the same identifier or version label. Choose another and try again.");
		}
	}

	/** The value set a SNOMED CT identifier names, if the person may see it; otherwise a 404. */
	public ValueSetView getByIdentifier(String sctid, CurrentUser user) {
		return identifiers.valueSetIdentifiedBy(sctid)
				.map(id -> get(id, user))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Value set not found"));
	}

	/** A value set the person may see, or a 404 as if it did not exist. */
	public ValueSet visible(long id, CurrentUser user) {
		return findVisible(id, user);
	}

	public ValueSetView get(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		return view(valueSet, latestVersionOf(valueSet), user);
	}

	public ValueSetView create(ValueSetDefinition definition, CurrentUser user) {
		requireEclIfNeeded(definition);
		boolean manual = definition.type() == ValueSet.DefinitionType.MANUAL;
		String branch = definition.codeSystemVersion() == null
				? releaseBranchOf(definition.codeSystem())
				: releaseBranchOf(definition.codeSystem(), definition.codeSystemVersion());
		List<Concept> concepts = manual ? resolveConcepts(definition, branch, List.of()) : List.of();
		String ecl = manual ? "" : withTerms(definition.ecl(), branch);
		SetIdentifier identifier = definition.sctid() == null ? null
				: identifiers.check(definition.sctid(), branch, null, user);

		ValueSet created = saveUniquely(() -> transaction.execute(status -> {
			ValueSet valueSet = new ValueSet(definition.type(), definition.codeSystem(), branch,
					definition.name(), definition.description(), ecl,
					definition.visibility(), definition.tags(), user.subject(), user.name(),
					definition.showOrganisation());
			valueSet.identifyAs(identifier);
			valueSet = valueSets.save(valueSet);
			saveWorkingList(valueSet, concepts);
			return valueSet;
		}));
		return view(created, null, user);
	}

	public ValueSetView update(long id, ValueSetDefinition definition, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		if (definition.type() != valueSet.getDefinitionType()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A value set cannot be changed between an ECL definition and a hand-picked list.");
		}
		if (!definition.codeSystem().equals(valueSet.getCodeSystem())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The code system of a value set cannot be changed.");
		}
		requireEclIfNeeded(definition);
		List<Concept> concepts = valueSet.isManual()
				? resolveConcepts(definition, valueSet.getBranch(), workingList(valueSet))
				: List.of();

		String ecl = valueSet.isManual() ? "" : withTerms(definition.ecl(), valueSet.getBranch());
		// Snowstorm is only asked about an identifier that is new or changed.
		boolean identifierChanged = !Objects.equals(definition.sctid(), valueSet.getSctid());
		SetIdentifier identifier = identifierChanged && definition.sctid() != null
				? identifiers.check(definition.sctid(), valueSet.getBranch(), valueSet.getId(), user)
				: null;

		saveUniquely(() -> transaction.execute(status -> {
			valueSet.update(definition.name(), definition.description(), ecl, definition.visibility(),
					definition.tags(), definition.showOrganisation());
			if (identifierChanged) {
				valueSet.identifyAs(identifier);
			}
			valueSets.save(valueSet);
			if (valueSet.isManual()) {
				members.deleteWorkingList(valueSet.getId());
				saveWorkingList(valueSet, concepts);
			}
			return valueSet;
		}));
		return view(valueSet, latestVersionOf(valueSet), user);
	}

	public void delete(long id, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		// A published version may be in use or referred to, so it is kept until the owner has
		// deprecated it. Once every version is deprecated the owner may remove the set entirely.
		if (allVersionsOf(valueSet).stream().anyMatch(version -> !version.isDeprecated())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A value set with a published version in use cannot be deleted. Deprecate every version first.");
		}
		valueSets.delete(valueSet);
	}

	/** Throws away the changes made since the version in use, so the draft matches it again. */
	public ValueSetView discardChanges(long id, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		ValueSetVersion published = latestVersionOf(valueSet);
		if (published == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This value set has not been published, so there is no version to go back to. It can be deleted instead.");
		}
		transaction.executeWithoutResult(status -> {
			valueSet.revertEclTo(published.getEcl());
			valueSets.save(valueSet);
			if (valueSet.isManual()) {
				members.deleteWorkingList(valueSet.getId());
				members.copyVersionToWorkingList(valueSet.getId(), published.getVersionNumber());
			}
		});
		return view(valueSet, published, user);
	}

	/** Marks a published value set as not to be used any more. It stays readable. */
	public ValueSetView deprecate(long id, String reason, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		if (allVersionsOf(valueSet).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Only a published value set can be deprecated. An unpublished one can be deleted.");
		}
		valueSet.deprecate(reason);
		return view(valueSets.save(valueSet), latestVersionOf(valueSet), user);
	}

	public ValueSetView restore(long id, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		valueSet.restore();
		return view(valueSets.save(valueSet), latestVersionOf(valueSet), user);
	}

	/** Marks one published version as not to be used. Other people then see the newest version that is not deprecated. */
	public VersionView deprecateVersion(long id, int versionNumber, String reason, CurrentUser user) {
		ValueSetVersion version = findOwnedVersion(id, versionNumber, user);
		version.deprecate(reason);
		return VersionView.of(versions.save(version));
	}

	public VersionView restoreVersion(long id, int versionNumber, CurrentUser user) {
		ValueSetVersion version = findOwnedVersion(id, versionNumber, user);
		version.restore();
		return VersionView.of(versions.save(version));
	}

	private ValueSetVersion findOwnedVersion(long id, int versionNumber, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		return versions.findByValueSetIdAndVersionNumber(valueSet.getId(), versionNumber)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
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
	/**
	 * @param language a language code such as "no" for preferred terms in that language where the
	 *        concepts have them; null or "en" for US English, as stored
	 */
	public ExpansionPage expansion(long id, CurrentUser user, int offset, int limit, String language) {
		ValueSet valueSet = findVisible(id, user);
		ValueSetVersion latest = latestVersionOf(valueSet);
		boolean showPublished = latest != null && !valueSet.isOwnedBy(user.subject());
		if (showPublished) {
			return versionContent(valueSet, latest, offset, limit, language);
		}
		return valueSet.isManual()
				? inLanguage(memberPage(valueSet, ValueSetMember.WORKING_LIST, offset, limit), valueSet.getBranch(), language)
				: liveExpansion(valueSet.getEcl(), valueSet.getBranch(), offset, limit, language);
	}

	/**
	 * The edition's own language, which a set's concepts are shown in by default, and every
	 * language the edition holds. English only when Snowstorm cannot say.
	 */
	public SetLanguages languages(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		try {
			Edition edition = snowstorm.editionFor(valueSet.getBranch());
			if (edition != null && edition.languages() != null && !edition.languages().isEmpty()) {
				List<SetLanguages.Language> available = edition.languages().entrySet().stream()
						.map(entry -> new SetLanguages.Language(entry.getKey(), entry.getValue()))
						.sorted(Comparator.comparing((SetLanguages.Language language) -> !language.code().equals(edition.language()))
								.thenComparing(SetLanguages.Language::name))
						.toList();
				return new SetLanguages(edition.language(), available);
			}
		} catch (SnowstormException e) {
			// The list is still shown, in English.
		}
		return new SetLanguages("en", List.of(new SetLanguages.Language("en", "English")));
	}

	/** What an ECL definition expands to now, with preferred terms in the language asked for. */
	private ExpansionPage liveExpansion(String ecl, String branch, int offset, int limit, String language) {
		return language == null || language.equals("en")
				? snowstorm.expand(ecl, branch, offset, limit)
				: snowstorm.expand(ecl, branch, offset, limit, language);
	}

	/**
	 * A page of a stored list with its preferred terms in another language, looked up for just
	 * that page. A concept with no term in the language, an inactive one, or every concept if
	 * Snowstorm cannot be reached, keeps its stored US English term, marked as English.
	 */
	private ExpansionPage inLanguage(ExpansionPage page, String branch, String language) {
		if (language == null || language.equals("en") || page.items().isEmpty()) {
			return page;
		}
		Map<String, Concept> translated;
		try {
			translated = snowstorm.activeConcepts(page.items().stream().map(Concept::conceptId).toList(), branch, language)
					.items().stream()
					.collect(Collectors.toMap(Concept::conceptId, Function.identity(), (a, b) -> a));
		} catch (SnowstormException e) {
			return page;
		}
		return new ExpansionPage(page.items().stream()
				.map(stored -> {
					Concept found = translated.get(stored.conceptId());
					return found == null || found.preferredTerm() == null ? stored
							: new Concept(stored.conceptId(), found.preferredTerm(), stored.fullySpecifiedName(),
									found.language(), stored.active());
				})
				.toList(), page.total(), page.offset(), page.limit());
	}

	/**
	 * A hand-picked list as the caller sees it, with the branch its concepts belong to: the
	 * working list for the owner, the version in use for everyone else, as {@link #expansion} does.
	 */
	CurrentList currentList(ValueSet valueSet, CurrentUser user) {
		ValueSetVersion latest = latestVersionOf(valueSet);
		if (latest != null && !valueSet.isOwnedBy(user.subject())) {
			List<Concept> concepts = members.findByValueSetIdAndVersionNumberOrderByPreferredTermAscConceptIdAsc(
					valueSet.getId(), latest.getVersionNumber()).stream().map(ValueSetMember::toConcept).toList();
			return new CurrentList(concepts, latest.getBranch());
		}
		return new CurrentList(workingList(valueSet), valueSet.getBranch());
	}

	record CurrentList(List<Concept> concepts, String branch) {
	}

	/** A published version is read from its stored concept list, so it never changes. */
	private ExpansionPage versionContent(ValueSet valueSet, ValueSetVersion version, int offset, int limit,
			String language) {
		return version.isContentFrozen()
				? inLanguage(memberPage(valueSet, version.getVersionNumber(), offset, limit), version.getBranch(), language)
				: liveExpansion(version.getEcl(), version.getBranch(), offset, limit, language);
	}

	public List<VersionView> versions(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		return versions.findByValueSetIdOrderByVersionNumberDesc(valueSet.getId()).stream()
				.map(VersionView::of)
				.toList();
	}

	public ExpansionPage versionExpansion(long id, int versionNumber, CurrentUser user, int offset, int limit,
			String language) {
		ValueSet valueSet = findVisible(id, user);
		ValueSetVersion version = versions.findByValueSetIdAndVersionNumber(valueSet.getId(), versionNumber)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
		return versionContent(valueSet, version, offset, limit, language);
	}

	/**
	 * A version of a value set as a TSV file in the Global Patient Set layout.
	 *
	 * @param version a version number; "draft" for the working draft, which only the owner may
	 *        download; or null for the version other people are shown
	 */
	public GpsFile download(long id, String version, CurrentUser user) {
		return GpsFile.of(downloadable(id, version, user));
	}

	/**
	 * The same, as a FHIR R4 ValueSet resource, with the definition and the concept list as the expansion.
	 *
	 * @param baseUrl where this application is reached, used for the resource's canonical URL
	 */
	public FhirValueSet downloadFhir(long id, String version, CurrentUser user, String baseUrl) {
		// Visitors who have not signed in get the Global Patient Set layout only.
		if (!user.isSignedIn()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to download a value set as FHIR.");
		}
		DownloadContent download = downloadable(id, version, user);
		return FhirValueSet.of(download, baseUrl, editionModuleOf(download.valueSet().getCodeSystem()));
	}

	/** The edition's module, for the SNOMED CT version URI. A download still works without it. */
	private String editionModuleOf(String codeSystem) {
		try {
			return snowstorm.editionModuleId(codeSystem);
		}
		catch (SnowstormException e) {
			return null;
		}
	}

	/**
	 * Anyone who can see a value set may download any of its published versions. The working
	 * draft is the owner's, so only the owner may download it, private or not.
	 */
	private DownloadContent downloadable(long id, String version, CurrentUser user) {
		if (DownloadContent.DRAFT.equals(version)) {
			ValueSet valueSet = findOwned(id, user);
			Collection<Concept> concepts = valueSet.isManual()
					? workingList(valueSet)
					: expandFully(valueSet.getEcl(), valueSet.getBranch());
			return DownloadContent.draftOf(valueSet, concepts);
		}
		ValueSet valueSet = findVisible(id, user);
		ValueSetVersion chosen = version == null
				? latestVersionOf(valueSet)
				: versions.findByValueSetIdAndVersionNumber(valueSet.getId(), Integer.parseInt(version))
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
		if (chosen == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This value set has not been published yet, so there is no version to download.");
		}
		Collection<Concept> concepts = chosen.isContentFrozen()
				? members.findByValueSetIdAndVersionNumberOrderByPreferredTermAscConceptIdAsc(valueSet.getId(),
						chosen.getVersionNumber()).stream().map(ValueSetMember::toConcept).toList()
				: expandFully(chosen.getEcl(), chosen.getBranch());
		return DownloadContent.of(valueSet, chosen, concepts);
	}

	/**
	 * @param label a name for the new version, shown instead of its number. Null for the default,
	 *        the date of the release it is built on; blank for no name, so the number is shown.
	 */
	public VersionView publish(long id, String note, String label, CurrentUser user) {
		ValueSet valueSet = findOwned(id, user);
		String tidyLabel = label == null
				? VersionLabels.defaultFor(valueSet.getBranch(), allVersionsOf(valueSet).stream().map(ValueSetVersion::getLabel).toList())
				: VersionLabels.tidy(label);
		if (valueSet.isDeprecated()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This value set is deprecated. Restore it before publishing a new version.");
		}
		List<ValueSetVersion> all = allVersionsOf(valueSet);
		ValueSetVersion latest = versionInUse(all);
		if (latest != null && !differsFrom(valueSet, latest)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Nothing has changed since version " + latest.getVersionNumber() + ".");
		}
		if (valueSet.isManual()) {
			requireMembers(members.countByValueSetIdAndVersionNumber(valueSet.getId(), ValueSetMember.WORKING_LIST));
			requireLabelFree(all, tidyLabel, null);
			return VersionView.of(saveUniquely(() -> transaction.execute(status -> recordVersion(valueSet, null, note, tidyLabel))));
		}
		requireLabelFree(all, tidyLabel, null);
		// Only an ECL definition that Snowstorm can expand may be published.
		Collection<Concept> concepts = expandFully(valueSet.getEcl(), valueSet.getBranch());
		return VersionView.of(saveUniquely(() -> transaction.execute(status -> recordVersion(valueSet, concepts, note, tidyLabel))));
	}

	/** Names a version, or with a blank label goes back to showing its number. Owner only. */
	public VersionView relabelVersion(long id, int versionNumber, String label, CurrentUser user) {
		ValueSetVersion version = findOwnedVersion(id, versionNumber, user);
		String tidyLabel = VersionLabels.tidy(label);
		requireLabelFree(versions.findByValueSetIdOrderByVersionNumberDesc(id), tidyLabel, versionNumber);
		version.relabel(tidyLabel);
		return VersionView.of(saveUniquely(() -> versions.save(version)));
	}

	/** Two versions of one set may not share a label, whatever its capitals, or no one could tell them apart. */
	private static void requireLabelFree(List<ValueSetVersion> all, String label, Integer except) {
		if (label == null) {
			return;
		}
		all.stream()
				.filter(version -> except == null || version.getVersionNumber() != except)
				.filter(version -> label.equalsIgnoreCase(version.getLabel()))
				.findFirst()
				.ifPresent(version -> {
					throw new ResponseStatusException(HttpStatus.CONFLICT,
							"Version " + version.getVersionNumber() + " is already called \"" + version.getLabel() + "\".");
				});
	}

	static void requireMembers(long count) {
		if (count == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one concept before publishing.");
		}
	}

	/**
	 * Stores the current definition as the next version. Must run inside a transaction.
	 *
	 * @param eclContent what an ECL definition expands to now; null for a hand-picked list,
	 *        whose working list is copied instead
	 */
	/** The same, named after the release the set is now on, as a version made by an upgrade is. */
	ValueSetVersion recordVersion(ValueSet valueSet, Collection<Concept> eclContent, String note) {
		return recordVersion(valueSet, eclContent, note, VersionLabels.defaultFor(valueSet.getBranch(),
				allVersionsOf(valueSet).stream().map(ValueSetVersion::getLabel).toList()));
	}

	ValueSetVersion recordVersion(ValueSet valueSet, Collection<Concept> eclContent, String note, String label) {
		List<ValueSetVersion> all = allVersionsOf(valueSet);
		// Numbers are never reused, so a deprecated version still counts.
		int number = all.isEmpty() ? 1 : all.get(0).getVersionNumber() + 1;
		valueSet.markPublished();
		valueSets.save(valueSet);
		long count;
		if (eclContent == null) {
			members.copyWorkingListToVersion(valueSet.getId(), number);
			count = members.countByValueSetIdAndVersionNumber(valueSet.getId(), number);
		}
		else {
			storeVersionContent(valueSet, number, eclContent);
			count = eclContent.size();
		}
		return versions.save(new ValueSetVersion(valueSet, number, count, note, label));
	}

	/** Every concept an ECL expression matches right now. */
	Collection<Concept> expandFully(String ecl, String branch) {
		// Keyed by concept so a concept that shows up on two pages is stored once.
		Map<String, Concept> concepts = new LinkedHashMap<>();
		ExpansionPage page;
		int offset = 0;
		do {
			page = snowstorm.expand(ecl, branch, offset, PUBLISH_PAGE_SIZE);
			if (page.total() > MAX_PUBLISHED_CONCEPTS) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This definition matches " + page.total()
						+ " concepts. At most " + MAX_PUBLISHED_CONCEPTS + " can be published or downloaded.");
			}
			page.items().forEach(concept -> concepts.put(concept.conceptId(), concept));
			offset += PUBLISH_PAGE_SIZE;
		}
		while (!page.items().isEmpty() && offset < page.total());
		return concepts.values();
	}

	private void storeVersionContent(ValueSet valueSet, int versionNumber, Collection<Concept> concepts) {
		// Plain JDBC batching: a version can hold thousands of rows.
		jdbc.batchUpdate("""
				INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
				                              preferred_term_language, fully_specified_name)
				VALUES (?, ?, ?, ?, ?, ?)
				""", concepts.stream()
				.map(concept -> new Object[] { valueSet.getId(), versionNumber, concept.conceptId(),
						concept.preferredTerm() == null ? "" : concept.preferredTerm(),
						ValueSetMember.languageOf(concept),
						concept.fullySpecifiedName() == null ? "" : concept.fullySpecifiedName() })
				.toList());
	}

	ValueSetView view(ValueSet valueSet, ValueSetVersion latest, CurrentUser user) {
		OwnerProfile owner = user.isSignedIn()
				? profiles.ownerProfile(valueSet.getOwnerSubject())
				: OwnerProfile.none(valueSet.getOwnerSubject());
		return view(valueSet, latest, user, owner);
	}

	private ValueSetView view(ValueSet valueSet, ValueSetVersion latest, CurrentUser user, OwnerProfile owner) {
		// Working this out costs a query for a hand-picked set, and only the owner is told.
		boolean unpublishedChanges = latest != null && valueSet.isOwnedBy(user.subject())
				&& differsFrom(valueSet, latest);
		return ValueSetView.of(valueSet, latest, unpublishedChanges, user, owner, messaging.enabled());
	}

	private boolean differsFrom(ValueSet valueSet, ValueSetVersion version) {
		// A move to another release is a change even when the concepts stayed the same.
		if (!version.getBranch().equals(valueSet.getBranch())) {
			return true;
		}
		if (valueSet.isManual()) {
			return !members.findConceptIds(valueSet.getId(), ValueSetMember.WORKING_LIST)
					.equals(members.findConceptIds(valueSet.getId(), version.getVersionNumber()));
		}
		return !version.getEcl().equals(valueSet.getEcl());
	}

	/**
	 * Writes the preferred term after each concept identifier that was typed without one,
	 * so the stored expression can be read without looking the identifiers up.
	 */
	private String withTerms(String ecl, String branch) {
		List<String> conceptIds = List.copyOf(EclTerms.conceptIdsWithoutTerm(ecl));
		Map<String, String> terms = new HashMap<>();
		try {
			for (int start = 0; start < conceptIds.size(); start += SNOWSTORM_BATCH_SIZE) {
				List<String> batch = conceptIds.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, conceptIds.size()));
				snowstorm.expand(String.join(" OR ", batch), branch, 0, SNOWSTORM_BATCH_SIZE).items()
						.forEach(concept -> terms.put(concept.conceptId(), concept.preferredTerm()));
			}
		}
		catch (SnowstormException e) {
			// Saving a draft must not depend on Snowstorm. The terms are added on a later save.
			return ecl;
		}
		String withTerms = EclTerms.addTerms(ecl, terms);
		return withTerms.length() <= MAX_ECL_LENGTH ? withTerms : ecl;
	}

	/**
	 * The branch of the newest release of a code system, so a new value set does not change
	 * with content that has not been released yet. A code system with no release yet has
	 * only its live branch.
	 */
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

	CodeSystem codeSystem(String shortName) {
		return snowstorm.codeSystems().stream()
				.filter(candidate -> candidate.shortName().equals(shortName))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Snowstorm has no code system called " + shortName + "."));
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
	private List<Concept> resolveConcepts(ValueSetDefinition definition, String branch, List<Concept> alreadyInList) {
		Map<String, Concept> known = alreadyInList.stream()
				.collect(Collectors.toMap(Concept::conceptId, Function.identity()));
		Set<String> requested = new LinkedHashSet<>(definition.conceptIds());
		List<String> unknown = requested.stream().filter(conceptId -> !known.containsKey(conceptId)).toList();

		for (int start = 0; start < unknown.size(); start += SNOWSTORM_BATCH_SIZE) {
			List<String> batch = unknown.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, unknown.size()));
			snowstorm.expand(String.join(" OR ", batch), branch, 0, SNOWSTORM_BATCH_SIZE).items()
					.forEach(concept -> known.put(concept.conceptId(), concept));
		}

		List<String> notFound = requested.stream().filter(conceptId -> !known.containsKey(conceptId)).toList();
		if (!notFound.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"These concepts are not active concepts in " + definition.codeSystem() + ": "
							+ String.join(", ", notFound.subList(0, Math.min(5, notFound.size()))));
		}
		return requested.stream().map(known::get).toList();
	}

	void saveWorkingList(ValueSet valueSet, List<Concept> concepts) {
		List<ValueSetMember> rows = new ArrayList<>();
		concepts.forEach(concept -> rows.add(new ValueSetMember(valueSet.getId(), concept)));
		members.saveAll(rows);
	}

	List<Concept> workingList(ValueSet valueSet) {
		return members.findByValueSetIdAndVersionNumberOrderByPreferredTermAscConceptIdAsc(valueSet.getId(),
				ValueSetMember.WORKING_LIST).stream().map(ValueSetMember::toConcept).toList();
	}

	private ExpansionPage memberPage(ValueSet valueSet, int versionNumber, int offset, int limit) {
		List<Concept> concepts = members.findPage(valueSet.getId(), versionNumber, offset, limit).stream()
				.map(ValueSetMember::toConcept).toList();
		long total = members.countByValueSetIdAndVersionNumber(valueSet.getId(), versionNumber);
		return new ExpansionPage(concepts, total, offset, limit);
	}

	/** Every version of a value set, newest first. */
	private List<ValueSetVersion> allVersionsOf(ValueSet valueSet) {
		return versions.findByValueSetIdOrderByVersionNumberDesc(valueSet.getId());
	}

	/**
	 * For each value set the person may see, the stored concept list they would be shown: the
	 * owner's working list of a hand-picked set, otherwise the version in use. Sets with no stored
	 * list, such as an ECL set that has never been published, are left out.
	 *
	 * @return by value set id, the concept list's version number and the branch it was built on
	 */
	Map<Long, ShownList> listsShownTo(CurrentUser user) {
		List<ValueSet> visible = user.isSignedIn()
				? valueSets.findAllVisibleTo(user.subject())
				: valueSets.findAllPublished();
		Map<Long, List<ValueSetVersion>> versionsById = versions
				.findByValueSetIdInOrderByVersionNumberDesc(visible.stream().map(ValueSet::getId).toList()).stream()
				.collect(Collectors.groupingBy(ValueSetVersion::getValueSetId));
		Map<Long, ShownList> lists = new LinkedHashMap<>();
		for (ValueSet valueSet : visible) {
			if (valueSet.isManual() && valueSet.isOwnedBy(user.subject())) {
				lists.put(valueSet.getId(), new ShownList(ValueSetMember.WORKING_LIST, valueSet.getBranch()));
				continue;
			}
			ValueSetVersion inUse = versionInUse(versionsById.getOrDefault(valueSet.getId(), List.of()));
			if (inUse != null && inUse.isContentFrozen()) {
				lists.put(valueSet.getId(), new ShownList(inUse.getVersionNumber(), inUse.getBranch()));
			}
		}
		return lists;
	}

	/** A stored concept list: which version of a set it is, and the branch it was built on. */
	record ShownList(int version, String branch) {
	}

	ValueSetVersion latestVersionOf(ValueSet valueSet) {
		return versionInUse(allVersionsOf(valueSet));
	}

	/**
	 * The version other people are shown: the newest one that is not deprecated. If every
	 * version is deprecated it is the newest of all, which is then shown as deprecated.
	 * Null if there are no versions.
	 *
	 * @param newestFirst all versions of one value set
	 */
	private static ValueSetVersion versionInUse(List<ValueSetVersion> newestFirst) {
		return newestFirst.stream()
				.filter(version -> !version.isDeprecated())
				.findFirst()
				.orElse(newestFirst.isEmpty() ? null : newestFirst.get(0));
	}

	ValueSet findVisible(long id, CurrentUser user) {
		// A set the caller may not see is reported as missing, so its existence is not revealed.
		var found = user.isSignedIn() ? valueSets.findVisibleById(id, user.subject()) : valueSets.findPublishedById(id);
		return found
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Value set not found"));
	}

	/** Only the owner may change, publish or delete a set, even one that everybody can see. */
	ValueSet findOwned(long id, CurrentUser user) {
		ValueSet valueSet = findVisible(id, user);
		if (!valueSet.isOwnedBy(user.subject())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can change this value set.");
		}
		return valueSet;
	}
}
