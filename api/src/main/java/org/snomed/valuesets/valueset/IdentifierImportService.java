package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.Inactivation;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.valueset.IdentifierCheck.Entry;
import org.snomed.valuesets.valueset.IdentifierCheck.Found;
import org.snomed.valuesets.valueset.IdentifierCheck.Inactive;
import org.snomed.valuesets.valueset.IdentifierCheck.Reason;
import org.snomed.valuesets.valueset.IdentifierCheck.Rejected;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks a list of identifiers pasted or uploaded to build a hand-picked list. Nothing is saved:
 * the person reviews the result, and the concepts they keep are saved with the value set as
 * though they had picked them one by one, which checks them again.
 */
@Service
public class IdentifierImportService {

	private final SnowstormClient snowstorm;
	private final ConceptLookup lookup;

	public IdentifierImportService(SnowstormClient snowstorm, ConceptLookup lookup) {
		this.snowstorm = snowstorm;
		this.lookup = lookup;
	}

	public IdentifierCheck check(IdentifierCheck.Request request) {
		String branch = request.branch();
		List<Rejected> rejected = new ArrayList<>();
		// Each distinct identifier once, in list order, with the first term the list gave it.
		Map<String, String> concepts = new LinkedHashMap<>();
		Map<String, String> descriptions = new LinkedHashMap<>();
		int duplicates = 0;

		for (Entry entry : request.entries()) {
			String identifier = entry.identifier().strip();
			if (concepts.containsKey(identifier) || descriptions.containsKey(identifier)) {
				duplicates++;
				continue;
			}
			Reason problem = problemWith(identifier);
			if (problem != null) {
				rejected.add(new Rejected(identifier, problem));
			}
			else if (SctId.kindOf(identifier) == SctId.Kind.DESCRIPTION) {
				descriptions.put(identifier, termOf(entry));
			}
			else {
				concepts.put(identifier, termOf(entry));
			}
		}

		// Description identifiers become their concepts, keeping where they came from.
		Map<String, String> descriptionOf = new LinkedHashMap<>();
		Map<String, Optional<String>> owners = lookup.inParallel(descriptions.keySet(),
				descriptionId -> snowstorm.conceptIdOfDescription(descriptionId, branch));
		Map<String, String> wanted = new LinkedHashMap<>(concepts);
		for (Map.Entry<String, Optional<String>> owner : owners.entrySet()) {
			if (owner.getValue().isEmpty()) {
				rejected.add(new Rejected(owner.getKey(), Reason.NOT_IN_RELEASE));
			}
			else if (wanted.containsKey(owner.getValue().get())) {
				duplicates++;
			}
			else {
				wanted.put(owner.getValue().get(), descriptions.get(owner.getKey()));
				descriptionOf.put(owner.getValue().get(), owner.getKey());
			}
		}

		Map<String, Concept> active = lookup.activeConcepts(List.copyOf(wanted.keySet()), branch);
		List<Found> found = new ArrayList<>();
		List<String> notActive = new ArrayList<>();
		wanted.forEach((conceptId, suppliedTerm) -> {
			Concept concept = active.get(conceptId);
			if (concept == null) {
				notActive.add(conceptId);
				return;
			}
			found.add(new Found(concept.conceptId(), concept.preferredTerm(), concept.fullySpecifiedName(),
					concept.language(), descriptionOf.get(conceptId), suppliedTerm, differs(suppliedTerm, concept)));
		});

		Map<String, Inactivation> inactivations = new LinkedHashMap<>();
		lookup.inactivations(notActive, branch).forEach((conceptId, inactivation) -> {
			// Snowstorm only answers for concepts it has, so anything else is not in this release.
			if (ConceptLookup.NOT_IN_RELEASE.equals(inactivation.indicator()) || inactivation.active()) {
				rejected.add(new Rejected(descriptionOf.getOrDefault(conceptId, conceptId), Reason.NOT_IN_RELEASE));
			}
			else {
				inactivations.put(conceptId, inactivation);
			}
		});
		Set<String> foundIds = new HashSet<>(found.stream().map(Found::conceptId).toList());
		ConceptLookup.Replacements replacements = lookup.replacements(inactivations, branch, foundIds);
		List<Inactive> inactive = inactivations.entrySet().stream()
				.map(entry -> new Inactive(entry.getKey(), entry.getValue().term(), entry.getValue().indicator(),
						descriptionOf.get(entry.getKey()), replacements.byConcept().get(entry.getKey())))
				.toList();

		return new IdentifierCheck(branch, request.entries().size(), duplicates, found, inactive, rejected);
	}

	/**
	 * The concepts an ECL expression matches now, as a one-off way to fill a hand-picked list.
	 * The list does not follow the expression afterwards; that is what an ECL value set is for.
	 */
	public IdentifierCheck checkEcl(IdentifierCheck.EclRequest request) {
		ExpansionPage page = snowstorm.expand(request.ecl(), request.branch(), 0, ValueSetDefinition.MAX_MEMBERS);
		if (page.total() > ValueSetDefinition.MAX_MEMBERS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This expression matches " + page.total()
					+ " concepts, and a list can hold at most " + ValueSetDefinition.MAX_MEMBERS
					+ ". Narrow it down, or create a value set defined by ECL instead.");
		}
		List<Found> found = page.items().stream()
				.map(concept -> new Found(concept.conceptId(), concept.preferredTerm(), concept.fullySpecifiedName(),
						concept.language(), null, null, false))
				.toList();
		return new IdentifierCheck(request.branch(), found.size(), 0, found, List.of(), List.of());
	}

	private static Reason problemWith(String identifier) {
		if (!SctId.hasValidShape(identifier)) {
			return Reason.NOT_AN_IDENTIFIER;
		}
		if (!SctId.hasValidCheckDigit(identifier)) {
			return Reason.INVALID_CHECK_DIGIT;
		}
		return SctId.kindOf(identifier) == SctId.Kind.OTHER ? Reason.NOT_A_CONCEPT : null;
	}

	private static String termOf(Entry entry) {
		return entry.term() == null || entry.term().isBlank() ? null : entry.term().strip();
	}

	/**
	 * Only the preferred term and fully specified name are at hand, so a synonym counts as
	 * different. Case is ignored, and so is the semantic tag.
	 */
	private static boolean differs(String suppliedTerm, Concept concept) {
		if (suppliedTerm == null) {
			return false;
		}
		String supplied = normalised(suppliedTerm);
		return !supplied.equals(normalised(concept.preferredTerm()))
				&& !supplied.equals(normalised(concept.fullySpecifiedName()))
				&& !supplied.equals(normalised(withoutSemanticTag(concept.fullySpecifiedName())));
	}

	private static String withoutSemanticTag(String fullySpecifiedName) {
		return fullySpecifiedName == null ? null : fullySpecifiedName.replaceFirst("\\s*\\([^()]*\\)$", "");
	}

	private static String normalised(String term) {
		return term == null ? "" : term.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}
}
