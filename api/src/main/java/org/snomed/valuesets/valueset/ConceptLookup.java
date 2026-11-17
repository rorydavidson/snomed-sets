package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.Inactivation;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.valueset.UpgradePreview.Replacement;
import org.springframework.stereotype.Component;

/**
 * Looks up many concepts on a release branch at once: which are active, and for the rest,
 * why they were made inactive and what replaces them. Shared by upgrades and imports.
 */
@Component
class ConceptLookup {

	/** Reported as the inactivation reason for a concept the release does not have at all. */
	static final String NOT_IN_RELEASE = "NOT_IN_RELEASE";
	private static final int SNOWSTORM_BATCH_SIZE = 100;
	/** Snowstorm has no call for many concepts' associations at once, so a few are asked for at a time. */
	private static final int PARALLEL_LOOKUPS = 8;

	private final SnowstormClient snowstorm;

	ConceptLookup(SnowstormClient snowstorm) {
		this.snowstorm = snowstorm;
	}

	/** The given concepts that are active on a branch, with their terms there. */
	Map<String, Concept> activeConcepts(List<String> conceptIds, String branch) {
		Map<String, Concept> found = new HashMap<>();
		for (int start = 0; start < conceptIds.size(); start += SNOWSTORM_BATCH_SIZE) {
			List<String> batch = conceptIds.subList(start, Math.min(start + SNOWSTORM_BATCH_SIZE, conceptIds.size()));
			snowstorm.activeConcepts(batch, branch).items().forEach(concept -> found.put(concept.conceptId(), concept));
		}
		return found;
	}

	/**
	 * Why each concept is inactive. A concept the branch does not have at all, for example one
	 * from another extension, comes back with {@link #NOT_IN_RELEASE} as its reason.
	 */
	Map<String, Inactivation> inactivations(Collection<String> conceptIds, String branch) {
		return inParallel(conceptIds, conceptId -> inactivationOf(conceptId, branch));
	}

	/**
	 * The active concepts the historical associations suggest in place of each inactive one,
	 * sorted by association so the same release always gives the same order.
	 *
	 * @param inList concepts already in the list, which are marked so that accepting them adds nothing
	 */
	Replacements replacements(Map<String, Inactivation> inactivations, String branch, Set<String> inList) {
		Set<String> targetIds = new LinkedHashSet<>();
		inactivations.values().forEach(inactivation -> inactivation.associationTargets().values()
				.forEach(targetIds::addAll));
		// A suggested replacement that is itself inactive is not offered.
		Map<String, Concept> activeTargets = activeConcepts(List.copyOf(targetIds), branch);

		Map<String, List<Replacement>> found = new LinkedHashMap<>();
		inactivations.forEach((conceptId, inactivation) -> {
			List<Replacement> replacements = new ArrayList<>();
			new TreeMap<>(inactivation.associationTargets()).forEach((association, targets) -> targets.stream()
					.distinct()
					.filter(activeTargets::containsKey)
					.map(activeTargets::get)
					.forEach(target -> replacements.add(new Replacement(target.conceptId(), target.preferredTerm(),
							association, inList.contains(target.conceptId())))));
			found.put(conceptId, replacements);
		});
		return new Replacements(found, activeTargets);
	}

	/**
	 * @param byConcept for each inactive concept, what could replace it
	 * @param concepts every suggested replacement, as Snowstorm gave it
	 */
	record Replacements(Map<String, List<Replacement>> byConcept, Map<String, Concept> concepts) {
	}

	/** Runs a Snowstorm call for each identifier, a few at a time, keeping the identifiers' order. */
	<T> Map<String, T> inParallel(Collection<String> ids, Function<String, T> call) {
		Map<String, Future<T>> pending = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_LOOKUPS, Thread.ofVirtual().factory())) {
			ids.forEach(id -> pending.computeIfAbsent(id, key -> executor.submit(() -> call.apply(key))));
		}
		Map<String, T> found = new LinkedHashMap<>();
		pending.forEach((id, future) -> found.put(id, resultOf(future)));
		return found;
	}

	private Inactivation inactivationOf(String conceptId, String branch) {
		try {
			return snowstorm.inactivation(conceptId, branch);
		}
		catch (SnowstormException e) {
			if (e.isCausedByRequest()) {
				return new Inactivation(false, null, NOT_IN_RELEASE, Map.of());
			}
			throw e;
		}
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
			throw new SnowstormException("Looking up concepts was interrupted.", false, e);
		}
	}
}
