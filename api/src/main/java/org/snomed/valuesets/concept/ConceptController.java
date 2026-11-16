package org.snomed.valuesets.concept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.ConceptDetails;
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

	/** Everything shown when a person clicks on a concept. */
	@GetMapping("/{conceptId}")
	public ConceptDetails details(
			@PathVariable @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String conceptId,
			@RequestParam(defaultValue = "MAIN") String branch) {
		return snowstorm.conceptDetails(conceptId, branch);
	}

	/** The direct children of a concept, up to {@link #MAX_CHILDREN}. The total says if there are more. */
	@GetMapping("/{conceptId}/children")
	public Children children(
			@PathVariable @Pattern(regexp = ValueSetDefinition.CONCEPT_ID_REGEX) String conceptId,
			@RequestParam(defaultValue = "MAIN") String branch) {
		List<Concept> children = new ArrayList<>();
		long total;
		do {
			ExpansionPage page = snowstorm.expand("<! " + conceptId, branch, children.size(), PAGE_SIZE);
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
