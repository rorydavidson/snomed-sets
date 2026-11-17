package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.Inactivation;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.UpgradePreview.Availability;
import org.snomed.valuesets.valueset.UpgradePreview.Change;
import org.snomed.valuesets.valueset.UpgradePreview.InactiveMember;
import org.snomed.valuesets.valueset.UpgradePreview.Replacement;
import org.snomed.valuesets.valueset.UpgradePreview.TermChange;
import org.snomed.valuesets.valueset.UpgradePreview.UpgradeView;
import org.snomed.valuesets.valueset.ValueSetUpgradeChange.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moves a value set onto a newer release of its code system.
 *
 * The preview and the upgrade work the changes out the same way, from release branches that
 * never change, so what is saved is what was previewed. The upgrade works them out again
 * rather than taking them from the browser, and only adds replacements it suggested itself.
 */
@Service
public class ValueSetUpgradeService {

	private static final int SNOWSTORM_BATCH_SIZE = 100;
	/** Inactive concepts are looked up one by one, so a few at a time to spare Snowstorm. */
	private static final int PARALLEL_LOOKUPS = 8;
	private static final String NOT_IN_RELEASE = "NOT_IN_RELEASE";
	private static final Comparator<Concept> BY_TERM = Comparator
			.comparing(Concept::preferredTerm, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Concept::conceptId);

	private final ValueSetService valueSetService;
	private final ValueSetRepository valueSets;
	private final ValueSetMemberRepository members;
	private final ValueSetUpgradeRepository upgrades;
	private final ValueSetUpgradeChangeRepository changes;
	private final SnowstormClient snowstorm;
	private final TransactionTemplate transaction;

	public ValueSetUpgradeService(ValueSetService valueSetService, ValueSetRepository valueSets,
			ValueSetMemberRepository members, ValueSetUpgradeRepository upgrades,
			ValueSetUpgradeChangeRepository changes, SnowstormClient snowstorm, TransactionTemplate transaction) {
		this.valueSetService = valueSetService;
		this.valueSets = valueSets;
		this.members = members;
		this.upgrades = upgrades;
		this.changes = changes;
		this.snowstorm = snowstorm;
		this.transaction = transaction;
	}

	/** Only the owner can upgrade, so only the owner is told whether there is something to upgrade to. */
	public Availability availability(long id, CurrentUser user) {
		ValueSet valueSet = valueSetService.findOwned(id, user);
		return availabilityOf(valueSet, valueSetService.codeSystem(valueSet.getCodeSystem()));
	}

	public UpgradePreview preview(long id, CurrentUser user) {
		ValueSet valueSet = valueSetService.findOwned(id, user);
		return plan(valueSet, targetOf(valueSet, null).latestVersionBranch()).preview();
	}

	public UpgradePreview.Result upgrade(long id, UpgradeRequest request, CurrentUser user) {
		ValueSet valueSet = valueSetService.findOwned(id, user);
		if (request.publish() && valueSet.isDeprecated()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This value set is deprecated. Restore it before publishing a new version.");
		}
		String toBranch = targetOf(valueSet, request.targetVersion()).latestVersionBranch();
		Plan plan = plan(valueSet, toBranch);
		Set<String> accepted = acceptedReplacements(plan.preview(), request.acceptedReplacements());
		Set<String> kept = keptInactive(plan.preview(), request.keptInactiveConceptIds());
		List<Concept> newList = valueSet.isManual() ? newList(plan, accepted, kept) : List.of();
		if (valueSet.isManual() && request.publish()) {
			ValueSetService.requireMembers(newList.size());
		}
		List<Change> report = reportOf(plan.preview(), accepted, kept);
		String fromBranch = valueSet.getBranch();

		ValueSetUpgrade saved = transaction.execute(status -> {
			valueSet.moveTo(toBranch);
			valueSets.save(valueSet);
			if (valueSet.isManual()) {
				members.deleteWorkingList(valueSet.getId());
				valueSetService.saveWorkingList(valueSet, newList);
				// The version is copied from the working list in SQL, so the new list must be written first.
				members.flush();
			}
			Integer published = request.publish()
					? valueSetService.recordVersion(valueSet, valueSet.isManual() ? null : plan.eclContent(),
							request.note()).getVersionNumber()
					: null;
			ValueSetUpgrade upgrade = upgrades.save(new ValueSetUpgrade(valueSet.getId(), fromBranch, toBranch, published));
			changes.saveAll(report.stream().map(change -> new ValueSetUpgradeChange(upgrade.getId(), change)).toList());
			return upgrade;
		});
		Map<Type, Long> counts = report.stream()
				.collect(Collectors.groupingBy(Change::type, () -> new EnumMap<>(Type.class), Collectors.counting()));
		ValueSetView view = valueSetService.view(valueSet, valueSetService.latestVersionOf(valueSet), user);
		return new UpgradePreview.Result(view, viewOf(saved, counts));
	}

	/** Anyone who can see the value set can see how its upgrades changed it. */
	public List<UpgradeView> upgrades(long id, CurrentUser user) {
		ValueSet valueSet = valueSetService.findVisible(id, user);
		List<ValueSetUpgrade> found = upgrades.findByValueSetIdOrderByUpgradedAtDesc(valueSet.getId());
		if (found.isEmpty()) {
			return List.of();
		}
		Map<Long, Map<Type, Long>> counts = new HashMap<>();
		changes.countByType(found.stream().map(ValueSetUpgrade::getId).toList()).forEach(count -> counts
				.computeIfAbsent(count.getUpgradeId(), key -> new EnumMap<>(Type.class))
				.put(count.getType(), count.getCount()));
		return found.stream().map(upgrade -> viewOf(upgrade, counts.getOrDefault(upgrade.getId(), Map.of()))).toList();
	}

	public UpgradeReportCsv report(long id, long upgradeId, CurrentUser user) {
		ValueSet valueSet = valueSetService.findVisible(id, user);
		ValueSetUpgrade upgrade = upgrades.findByIdAndValueSetId(upgradeId, valueSet.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upgrade not found"));
		List<Change> lines = changes.findByUpgradeIdOrderByIdAsc(upgrade.getId()).stream()
				.map(ValueSetUpgradeChange::toChange)
				.toList();
		return UpgradeReportCsv.of(valueSet, upgrade, lines);
	}

	private static Availability availabilityOf(ValueSet valueSet, CodeSystem codeSystem) {
		String current = valueSet.getRelease();
		String latest = codeSystem.latestVersion();
		// Release names are dates written year first, so they sort as text.
		boolean available = latest != null && (current == null || latest.compareTo(current) > 0);
		return new Availability(codeSystem.shortName(), current, latest, available);
	}

	/**
	 * The release to upgrade to: always the newest one.
	 *
	 * @param previewedVersion the release the owner looked at before upgrading, or null for a preview.
	 *        If a newer release has come out since, the upgrade is refused rather than applying
	 *        changes the owner has not seen.
	 */
	private CodeSystem targetOf(ValueSet valueSet, String previewedVersion) {
		CodeSystem codeSystem = valueSetService.codeSystem(valueSet.getCodeSystem());
		if (!availabilityOf(valueSet, codeSystem).available()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, codeSystem.latestVersion() == null
					? codeSystem.shortName() + " has no release to upgrade to."
					: "This value set is already on the latest release of " + codeSystem.shortName() + ", "
							+ codeSystem.latestVersion() + ".");
		}
		if (previewedVersion != null && !previewedVersion.equals(codeSystem.latestVersion())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Release " + codeSystem.latestVersion() + " of "
					+ codeSystem.shortName() + " came out after the preview. Look at the changes again before upgrading.");
		}
		return codeSystem;
	}

	/**
	 * @param retained for a hand-picked list, the members that are still active, with their new terms
	 * @param inactive for a hand-picked list, the members inactive in the new release, as stored
	 * @param replacements for a hand-picked list, every suggested replacement as Snowstorm gave it
	 * @param eclContent for an ECL definition, everything it matches in the new release
	 */
	private record Plan(UpgradePreview preview, List<Concept> retained, Map<String, Concept> inactive,
			Map<String, Concept> replacements, Collection<Concept> eclContent) {
	}

	private Plan plan(ValueSet valueSet, String toBranch) {
		return valueSet.isManual() ? planHandPicked(valueSet, toBranch) : planEcl(valueSet, toBranch);
	}

	private Plan planEcl(ValueSet valueSet, String toBranch) {
		Map<String, Concept> before = byId(valueSetService.expandFully(valueSet.getEcl(), valueSet.getBranch()));
		Collection<Concept> afterList = valueSetService.expandFully(valueSet.getEcl(), toBranch);
		Map<String, Concept> after = byId(afterList);

		List<Concept> added = after.values().stream().filter(c -> !before.containsKey(c.conceptId())).sorted(BY_TERM).toList();
		List<Concept> removed = before.values().stream().filter(c -> !after.containsKey(c.conceptId())).sorted(BY_TERM).toList();
		List<TermChange> termChanges = termChanges(before, after.values());
		return new Plan(previewOf(valueSet, toBranch, before.size(), added, removed, termChanges, List.of()),
				List.of(), Map.of(), Map.of(), afterList);
	}

	private Plan planHandPicked(ValueSet valueSet, String toBranch) {
		List<Concept> current = valueSetService.workingList(valueSet);
		Map<String, Concept> active = activeConcepts(current.stream().map(Concept::conceptId).toList(), toBranch);

		// Stored terms are replaced by the new release's, so the list reads as the release does.
		List<Concept> retained = current.stream()
				.filter(concept -> active.containsKey(concept.conceptId()))
				.map(concept -> active.get(concept.conceptId()))
				.toList();
		Set<String> retainedIds = retained.stream().map(Concept::conceptId).collect(Collectors.toSet());
		List<Concept> inactive = current.stream().filter(concept -> !retainedIds.contains(concept.conceptId())).toList();

		Map<String, Inactivation> inactivations = inactivations(inactive, toBranch);
		Set<String> targetIds = inactivations.values().stream()
				.flatMap(inactivation -> inactivation.associationTargets().values().stream())
				.flatMap(List::stream)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		// A suggested replacement that is itself inactive in the new release is not offered.
		Map<String, Concept> activeTargets = activeConcepts(List.copyOf(targetIds), toBranch);

		List<InactiveMember> inactivated = inactive.stream().map(concept -> {
			Inactivation inactivation = inactivations.get(concept.conceptId());
			List<Replacement> replacements = new ArrayList<>();
			// Sorted by association so the same release always gives the same order.
			new TreeMap<>(inactivation.associationTargets()).forEach((association, targets) -> targets.stream()
					.distinct()
					.filter(activeTargets::containsKey)
					.map(activeTargets::get)
					.forEach(target -> replacements.add(new Replacement(target.conceptId(), target.preferredTerm(),
							association, retainedIds.contains(target.conceptId())))));
			return new InactiveMember(concept.conceptId(), concept.preferredTerm(), inactivation.indicator(), replacements,
					!concept.active());
		}).toList();

		Map<String, Concept> before = byId(current);
		return new Plan(previewOf(valueSet, toBranch, current.size(), List.of(), List.of(), termChanges(before, retained),
				inactivated), retained, byId(inactive), activeTargets, List.of());
	}

	private static UpgradePreview previewOf(ValueSet valueSet, String toBranch, long countBefore, List<Concept> added,
			List<Concept> removed, List<TermChange> termChanges, List<InactiveMember> inactivated) {
		return new UpgradePreview(valueSet.getCodeSystem(), valueSet.getDefinitionType(), valueSet.getRelease(),
				ValueSet.releaseOf(toBranch), valueSet.getBranch(), toBranch, countBefore, added, removed, termChanges,
				inactivated);
	}

	private static List<TermChange> termChanges(Map<String, Concept> before, Collection<Concept> after) {
		return after.stream()
				.filter(concept -> before.containsKey(concept.conceptId()))
				.filter(concept -> !Objects.equals(before.get(concept.conceptId()).preferredTerm(), concept.preferredTerm()))
				.sorted(BY_TERM)
				.map(concept -> new TermChange(concept.conceptId(), before.get(concept.conceptId()).preferredTerm(),
						concept.preferredTerm()))
				.toList();
	}

	/** The given concepts that are active on a branch, with their terms there. */
	private Map<String, Concept> activeConcepts(List<String> conceptIds, String branch) {
		Map<String, Concept> found = new HashMap<>();
		for (int start = 0; start < conceptIds.size(); start += SNOWSTORM_BATCH_SIZE) {
			List<String> batch = conceptIds.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, conceptIds.size()));
			snowstorm.activeConcepts(batch, branch).items().forEach(concept -> found.put(concept.conceptId(), concept));
		}
		return found;
	}

	/** Snowstorm has no call for many concepts' associations at once, so they are asked for side by side. */
	private Map<String, Inactivation> inactivations(List<Concept> inactive, String branch) {
		Map<String, Future<Inactivation>> pending = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_LOOKUPS, Thread.ofVirtual().factory())) {
			inactive.forEach(concept -> pending.put(concept.conceptId(),
					executor.submit(() -> inactivationOf(concept.conceptId(), branch))));
		}
		Map<String, Inactivation> found = new HashMap<>();
		pending.forEach((conceptId, future) -> found.put(conceptId, resultOf(future)));
		return found;
	}

	private Inactivation inactivationOf(String conceptId, String branch) {
		try {
			return snowstorm.inactivation(conceptId, branch);
		}
		catch (SnowstormException e) {
			if (e.isCausedByRequest()) {
				// The concept is not in this release at all, for example one from another extension.
				return new Inactivation(false, NOT_IN_RELEASE, Map.of());
			}
			throw e;
		}
	}

	private static Inactivation resultOf(Future<Inactivation> future) {
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
			throw new SnowstormException("Looking up inactive concepts was interrupted.", false, e);
		}
	}

	/** Checks that every accepted replacement is one the plan suggested, and returns them as keys. */
	private static Set<String> acceptedReplacements(UpgradePreview preview, List<UpgradeRequest.Replacement> requested) {
		Set<String> offered = new LinkedHashSet<>();
		preview.inactivated().forEach(member -> member.replacements()
				.forEach(replacement -> offered.add(key(member.conceptId(), replacement.conceptId()))));
		Set<String> accepted = new LinkedHashSet<>();
		for (UpgradeRequest.Replacement replacement : requested) {
			String key = key(replacement.inactiveConceptId(), replacement.replacementConceptId());
			if (!offered.contains(key)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, replacement.replacementConceptId()
						+ " is not a suggested replacement for " + replacement.inactiveConceptId() + ".");
			}
			accepted.add(key);
		}
		return accepted;
	}

	/** Checks that every concept to keep is one the plan found inactive. */
	private static Set<String> keptInactive(UpgradePreview preview, List<String> requested) {
		Set<String> inactive = preview.inactivated().stream().map(InactiveMember::conceptId).collect(Collectors.toSet());
		for (String conceptId : requested) {
			if (!inactive.contains(conceptId)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						conceptId + " is not an inactive concept in this value set, so it cannot be kept as one.");
			}
		}
		return new LinkedHashSet<>(requested);
	}

	private static String key(String inactiveConceptId, String replacementConceptId) {
		return inactiveConceptId + ">" + replacementConceptId;
	}

	/**
	 * The members still active, the inactive ones the owner kept (flagged as inactive), then the
	 * accepted replacements that are not already in the list.
	 */
	private static List<Concept> newList(Plan plan, Set<String> accepted, Set<String> kept) {
		Map<String, Concept> list = new LinkedHashMap<>(byId(plan.retained()));
		kept.forEach(conceptId -> list.put(conceptId, plan.inactive().get(conceptId).asInactive()));
		plan.preview().inactivated().forEach(member -> member.replacements().stream()
				.filter(replacement -> accepted.contains(key(member.conceptId(), replacement.conceptId())))
				.forEach(replacement -> list.putIfAbsent(replacement.conceptId(),
						plan.replacements().get(replacement.conceptId()))));
		return List.copyOf(list.values());
	}

	private static List<Change> reportOf(UpgradePreview preview, Set<String> accepted, Set<String> kept) {
		List<Change> report = new ArrayList<>();
		preview.added().forEach(concept -> report.add(new Change(Type.ADDED, concept.conceptId(), concept.preferredTerm(),
				null, null, null)));
		preview.removed().forEach(concept -> report.add(new Change(Type.REMOVED, concept.conceptId(),
				concept.preferredTerm(), null, null, null)));
		for (InactiveMember member : preview.inactivated()) {
			Type outcome = kept.contains(member.conceptId()) ? Type.INACTIVE_KEPT : Type.INACTIVATED;
			report.add(new Change(outcome, member.conceptId(), member.term(), null, null, member.reason()));
			member.replacements().stream().filter(replacement -> !replacement.alreadyInSet()).forEach(replacement -> {
				Type type = accepted.contains(key(member.conceptId(), replacement.conceptId()))
						? Type.REPLACEMENT_ADDED
						: Type.REPLACEMENT_DECLINED;
				report.add(new Change(type, replacement.conceptId(), replacement.term(), member.conceptId(), member.term(),
						replacement.association()));
			});
		}
		preview.termChanges().forEach(change -> report.add(new Change(Type.TERM_CHANGED, change.conceptId(),
				change.newTerm(), null, change.oldTerm(), null)));
		return report;
	}

	private static UpgradeView viewOf(ValueSetUpgrade upgrade, Map<Type, Long> counts) {
		return new UpgradeView(upgrade.getId(), upgrade.getFromVersion(), upgrade.getToVersion(),
				upgrade.getFromBranch(), upgrade.getToBranch(), upgrade.getUpgradedAt(), upgrade.getPublishedVersion(),
				counts);
	}

	private static Map<String, Concept> byId(Collection<Concept> concepts) {
		return concepts.stream().collect(Collectors.toMap(Concept::conceptId, Function.identity(), (a, b) -> a,
				LinkedHashMap::new));
	}
}
