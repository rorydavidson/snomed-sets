package org.snomed.valuesets.concept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.ConceptDetails;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.snomed.valuesets.valueset.ValueSetDefinition;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finding concepts to put in a hand-picked value set. */
@Validated
@RestController
@RequestMapping("/api/concepts")
public class ConceptController {

	private static final int PAGE_SIZE = 100;
	/** A concept can have thousands of children; adding them all in one go is capped. */
	static final int MAX_CHILDREN = 1000;

	private final SnowstormClient snowstorm;

	public ConceptController(SnowstormClient snowstorm) {
		this.snowstorm = snowstorm;
	}

	@GetMapping
	public ExpansionPage search(@RequestParam @Size(min = 3, max = 200) String term,
			@RequestParam(defaultValue = "MAIN") String branch,
			@RequestParam(defaultValue = "25") @Min(1) @Max(50) int limit) {
		return snowstorm.search(term, branch, limit);
	}

	/**
	 * How many direct children each of the given concepts has, for the search results.
	 * Snowstorm has no single call for this, so the concepts are asked about side by side.
	 */
	@GetMapping("/child-counts")
	public Map<String, Long> childCounts(
			@RequestParam @Size(min = 1, max = 50) List<@Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String> conceptIds,
			@RequestParam(defaultValue = "MAIN") String branch) throws InterruptedException {
		Map<String, Future<Long>> pending = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (String conceptId : conceptIds) {
				pending.computeIfAbsent(conceptId,
						id -> executor.submit(() -> snowstorm.expand("<! " + id, branch, 0, 1).total()));
			}
		}
		Map<String, Long> counts = new LinkedHashMap<>();
		for (Map.Entry<String, Future<Long>> entry : pending.entrySet()) {
			try {
				counts.put(entry.getKey(), entry.getValue().get());
			}
			catch (ExecutionException e) {
				if (e.getCause() instanceof SnowstormException snowstormFailure) {
					throw snowstormFailure;
				}
				throw new IllegalStateException(e.getCause());
			}
		}
		return counts;
	}

	/**
	 * Everything shown when a person clicks on a concept, with terms in the language of the
	 * edition the branch belongs to, and names for the modules and language reference sets
	 * its descriptions refer to.
	 */
	@GetMapping("/{conceptId}")
	public ConceptDetails details(
			@PathVariable @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String conceptId,
			@RequestParam(defaultValue = "MAIN") String branch) {
		Edition edition = editionOf(branch);
		ConceptDetails details = edition == null
				? snowstorm.conceptDetails(conceptId, branch)
				: snowstorm.conceptDetails(conceptId, branch, edition.language());
		Set<String> ids = new LinkedHashSet<>();
		ids.add(details.moduleId());
		for (ConceptDetails.Description description : details.descriptions()) {
			ids.add(description.moduleId());
			ids.addAll(description.preferredIn());
			ids.addAll(description.acceptableIn());
		}
		return details.withContext(edition, namesOf(ids, branch));
	}

	/** The edition, or null if Snowstorm cannot say; the concept is then shown in English. */
	private Edition editionOf(String branch) {
		try {
			return snowstorm.editionFor(branch);
		}
		catch (SnowstormException e) {
			return null;
		}
	}

	/**
	 * English names for the given concept identifiers. Labels such as en-US are not identifiers
	 * and are left out. Only a convenience: if the lookup fails, the identifiers are shown instead.
	 */
	private Map<String, String> namesOf(Set<String> ids, String branch) {
		List<String> conceptIds = ids.stream()
				.filter(id -> id != null && id.matches(ValueSetDefinition.CONCEPT_ID_REGEX))
				.toList();
		if (conceptIds.isEmpty()) {
			return Map.of();
		}
		try {
			ExpansionPage page = snowstorm.activeConcepts(conceptIds, branch);
			Map<String, String> names = new LinkedHashMap<>();
			if (page != null) {
				page.items().stream()
						.filter(concept -> concept.preferredTerm() != null)
						.forEach(concept -> names.put(concept.conceptId(), concept.preferredTerm()));
			}
			return names;
		}
		catch (SnowstormException e) {
			return Map.of();
		}
	}

	/** The direct children of a concept, up to {@link #MAX_CHILDREN}. The total says if there are more. */
	@GetMapping("/{conceptId}/children")
	public Children children(
			@PathVariable @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String conceptId,
			@RequestParam(defaultValue = "MAIN") String branch) {
		List<Concept> children = new ArrayList<>();
		Edition edition = editionOf(branch);
		long total;
		do {
			// In the edition's language, like the concept itself.
			ExpansionPage page = edition == null
					? snowstorm.expand("<! " + conceptId, branch, children.size(), PAGE_SIZE)
					: snowstorm.expand("<! " + conceptId, branch, children.size(), PAGE_SIZE, edition.language());
			children.addAll(page.items());
			total = page.total();
			if (page.items().isEmpty()) {
				break;
			}
		}
		while (children.size() < total && children.size() < MAX_CHILDREN);
		return new Children(children, total);
	}

	public record Children(List<Concept> items, long total) {
	}
}
