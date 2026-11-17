package org.snomed.valuesets.valueset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Works out an ECL expression that matches a hand-picked list, so the list can become a value
 * set defined by ECL. Nothing is saved.
 *
 * The list is covered from the top of the hierarchy down. A member whose descendants are all in
 * the list becomes {@code << X}. One whose subtree is missing a few concepts, or a few whole
 * branches, becomes {@code << X MINUS (...)} when that is shorter than naming its members. Any
 * other member is named on its own and the members below it are looked at in the same way. Then
 * members that are all the children of a concept outside the list become {@code < P}, or
 * {@code <! P} when only the children are listed. Whatever comes out is expanded and compared
 * with the list, so the result is never taken on trust.
 *
 * Snowstorm takes seconds to read an expression listing thousands of concepts, so questions
 * about the list narrow a small expression to its members with Snowstorm's concept filter
 * instead of writing the members into the expression.
 */
@Service
public class EclSuggestionService {

	/** At most this many exceptions in a {@code MINUS}, beyond which naming members is clearer. */
	static final int MAX_EXCEPTIONS = 10;
	/** A subtree missing more than this is not worth fetching to look for a few missing branches. */
	private static final int MAX_MISSING_FETCHED = 200;
	/** Snowstorm's largest page, more than any query here needs. */
	private static final int ALL = 10_000;
	private static final int PARENT_BATCH_SIZE = 500;
	private static final int DIFFERENCES_SHOWN = 50;

	private final ValueSetService valueSetService;
	private final SnowstormClient snowstorm;
	private final ConceptLookup lookup;

	public EclSuggestionService(ValueSetService valueSetService, SnowstormClient snowstorm, ConceptLookup lookup) {
		this.valueSetService = valueSetService;
		this.snowstorm = snowstorm;
		this.lookup = lookup;
	}

	public EclSuggestion suggest(long id, CurrentUser user) {
		ValueSet valueSet = valueSetService.findVisible(id, user);
		if (!valueSet.isManual()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This value set is already defined by ECL.");
		}
		ValueSetService.CurrentList list = valueSetService.currentList(valueSet, user);
		List<Concept> active = list.concepts().stream().filter(Concept::active).toList();
		List<Concept> inactive = list.concepts().stream().filter(concept -> !concept.active()).toList();
		if (active.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The list has no active concepts, so there is nothing for an expression to match.");
		}

		Work work = new Work(list.branch(), active);
		work.cover(work.topsOf(work.members));
		work.collapseSiblings();
		return work.checked(valueSet, work.expression(), inactive);
	}

	/** One clause covering part of the hierarchy, or one exception to it. */
	private record Clause(String operator, String conceptId, List<Clause> exceptions) {

		static Clause concept(String conceptId) {
			return new Clause("", conceptId, List.of());
		}

		static Clause subtree(String conceptId) {
			return new Clause("<<", conceptId, List.of());
		}
	}

	/** How a concept missing from the list relates to what is below it. */
	private enum Below {
		/** Nothing is below it. */
		NOTHING,
		/** Everything below it is missing too. */
		ALL_MISSING,
		/** Everything below it is listed. */
		ALL_LISTED,
		/** Some below it are listed and some are not. */
		MIXED
	}

	/** The state of one suggestion, kept together so the steps read in order. */
	private final class Work {

		private final String branch;
		private final Map<String, Concept> listed;
		private final Set<String> members;
		private final Map<String, String> terms = new ConcurrentHashMap<>();
		/** Clauses by the concept they start from. */
		private final Map<String, Clause> clauses = new LinkedHashMap<>();
		/** Members named on their own. */
		private final Set<String> named = new LinkedHashSet<>();
		/** Members already looked at as the top of part of the list; a concept with two parents can be reached twice. */
		private final Set<String> looked = new HashSet<>();

		Work(String branch, List<Concept> active) {
			this.branch = branch;
			this.listed = active.stream().collect(Collectors.toMap(Concept::conceptId, Function.identity(),
					(a, b) -> a, LinkedHashMap::new));
			this.members = listed.keySet();
			active.forEach(this::remember);
		}

		/** The concepts in a set that have no ancestor in the same set. */
		List<String> topsOf(Collection<String> scope) {
			if (scope.size() <= 1) {
				return List.copyOf(scope);
			}
			Set<String> below = new HashSet<>(ids(snowstorm.expandLong("< " + or(scope), branch, 0, ALL, scope)));
			return scope.stream().filter(conceptId -> !below.contains(conceptId)).toList();
		}

		void cover(List<String> tops) {
			List<String> fresh = tops.stream().filter(looked::add).toList();
			if (fresh.isEmpty()) {
				return;
			}
			// Tops with no children are named; asking about each one would cost a call apiece.
			Set<String> withChildren = new HashSet<>(
					ids(snowstorm.expandLong(">! (<! " + or(fresh) + ")", branch, 0, ALL, fresh)));
			fresh.stream().filter(top -> !withChildren.contains(top)).forEach(named::add);

			// Absent when the subtree cannot be described with a few exceptions.
			Map<String, Optional<List<Clause>>> outcomes = lookup.inParallel(
					fresh.stream().filter(withChildren::contains).toList(), this::exceptionsUnder);
			List<String> split = new ArrayList<>();
			outcomes.forEach((top, exceptions) -> {
				if (exceptions.isPresent()) {
					clauses.put(top, new Clause("<<", top, exceptions.get()));
				}
				else {
					named.add(top);
					split.add(top);
				}
			});
			// The members below each named one are looked at the same way, a level at a time.
			Map<String, List<String>> inner = lookup.inParallel(split, top -> topsOf(idsOfMembersIn("< " + top)));
			cover(inner.values().stream().flatMap(List::stream).distinct().toList());
		}

		/**
		 * What has to be taken out of a member's subtree to leave only members: nothing, a few whole
		 * branches, or a few single concepts. Empty when it would take more than that, or when
		 * naming the members would be shorter.
		 */
		private Optional<List<Clause>> exceptionsUnder(String top) {
			long size = count("<< " + top);
			long listedBelow = countMembersIn("<< " + top);
			long missingCount = size - listedBelow;
			if (missingCount == 0) {
				return Optional.of(List.of());
			}
			if (missingCount > MAX_MISSING_FETCHED) {
				return Optional.empty();
			}
			ExpansionPage subtree = snowstorm.expandLong("<< " + top, branch, 0, (int) size);
			subtree.items().forEach(this::remember);
			List<String> missing = ids(subtree).stream().filter(conceptId -> !members.contains(conceptId)).toList();
			List<String> missingTops = topsOf(missing);
			if (missingTops.size() > MAX_EXCEPTIONS) {
				return Optional.empty();
			}
			List<Clause> exceptions = new ArrayList<>();
			for (String missingTop : missingTops) {
				Below below = below(missingTop);
				if (below == Below.MIXED) {
					// Only single concepts can be taken out when listed and missing ones are mixed.
					if (missing.size() > MAX_EXCEPTIONS) {
						return Optional.empty();
					}
					exceptions = missing.stream().map(Clause::concept).toList();
					break;
				}
				exceptions.add(below == Below.ALL_MISSING ? Clause.subtree(missingTop) : Clause.concept(missingTop));
			}
			return listedBelow <= 1 + exceptions.size() ? Optional.empty() : Optional.of(exceptions);
		}

		private Below below(String missingTop) {
			long below = count("< " + missingTop);
			long listedBelow = countMembersIn("< " + missingTop);
			if (below == 0) {
				return Below.NOTHING;
			}
			if (listedBelow == 0) {
				return Below.ALL_MISSING;
			}
			return listedBelow == below ? Below.ALL_LISTED : Below.MIXED;
		}

		/**
		 * Members that are every child of the same concept outside the list become one clause:
		 * {@code < P} when everything below them is listed too, otherwise {@code <! P} for the
		 * children that were named one by one.
		 */
		void collapseSiblings() {
			Set<String> candidates = new LinkedHashSet<>();
			clauses.values().stream().filter(clause -> clause.exceptions().isEmpty())
					.forEach(clause -> candidates.add(clause.conceptId()));
			// A named member with something below it missing fails the check on its parent below.
			candidates.addAll(named);
			if (candidates.size() < 2) {
				return;
			}
			Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
			parentsOf(candidates).forEach((child, parents) -> parents.stream()
					.filter(parent -> !members.contains(parent))
					.forEach(parent -> childrenByParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(child)));

			List<String> shared = childrenByParent.entrySet().stream()
					.filter(entry -> entry.getValue().size() >= 2)
					.sorted(Comparator.comparing((Map.Entry<String, List<String>> entry) -> -entry.getValue().size()))
					.map(Map.Entry::getKey)
					.toList();
			Map<String, String> operators = lookup.inParallel(shared, this::siblingOperator);
			Set<String> covered = new HashSet<>();
			for (String parent : shared) {
				List<String> children = childrenByParent.get(parent);
				if (operators.get(parent).equals("<") && !covered.containsAll(children)) {
					covered.addAll(children);
					clauses.put(parent, new Clause("<", parent, List.of()));
				}
				else if (operators.get(parent).equals("<!")) {
					// Children described by their own clause keep it, as it covers more than the child.
					List<String> namedChildren = children.stream().filter(named::contains).toList();
					if (namedChildren.size() >= 2) {
						covered.addAll(namedChildren);
						clauses.put(parent, new Clause("<!", parent, List.of()));
					}
				}
			}
			covered.forEach(conceptId -> {
				clauses.remove(conceptId);
				named.remove(conceptId);
			});
		}

		/** {@code <} when the list has everything below a parent, {@code <!} when it has just its children, else none. */
		private String siblingOperator(String parent) {
			if (count("< " + parent) == countMembersIn("< " + parent)) {
				return "<";
			}
			return count("<! " + parent) == countMembersIn("<! " + parent) ? "<!" : "";
		}

		/** Loaded in batches in one go, as asking concept by concept is slow for a long list. */
		private Map<String, List<String>> parentsOf(Collection<String> conceptIds) {
			List<String> all = List.copyOf(conceptIds);
			Map<String, List<String>> parents = new HashMap<>();
			for (int start = 0; start < all.size(); start += PARENT_BATCH_SIZE) {
				parents.putAll(snowstorm.inferredParents(all.subList(start, Math.min(start + PARENT_BATCH_SIZE,
						all.size())), branch));
			}
			return parents;
		}

		/** Clauses first, then the named members, each in term order, one per line. */
		String expression() {
			// Parents outside the list were only ever seen as identifiers.
			List<String> withoutTerm = clauses.keySet().stream().filter(conceptId -> !terms.containsKey(conceptId)).toList();
			if (!withoutTerm.isEmpty()) {
				lookup.activeConcepts(withoutTerm, branch).values().forEach(this::remember);
			}
			Comparator<String> byTerm = Comparator.comparing(conceptId -> terms.getOrDefault(conceptId, conceptId),
					String.CASE_INSENSITIVE_ORDER);
			List<String> lines = new ArrayList<>();
			clauses.values().stream()
					.sorted(Comparator.comparing(Clause::conceptId, byTerm))
					.forEach(clause -> lines.add(text(clause)));
			named.stream().sorted(byTerm).forEach(conceptId -> lines.add(withTerm(conceptId)));
			return String.join(" OR\n", lines);
		}

		/** Expands the expression and compares it with the list. */
		EclSuggestion checked(ValueSet valueSet, String ecl, List<Concept> inactive) {
			long total = count(ecl);
			Set<String> matched = new HashSet<>(idsOfMembersIn(ecl));
			List<Concept> missing = members.stream().filter(conceptId -> !matched.contains(conceptId))
					.map(listed::get).toList();
			long extraCount = total - matched.size();
			List<Concept> extra = extraCount == 0 ? List.of()
					: snowstorm.expandLong(ecl, branch, 0, (int) Math.min(ALL, matched.size() + DIFFERENCES_SHOWN))
							.items().stream().filter(concept -> !members.contains(concept.conceptId()))
							.limit(DIFFERENCES_SHOWN).toList();
			return new EclSuggestion(valueSet.getCodeSystem(), ValueSet.releaseOf(branch), branch, ecl, clauses.size(),
					named.size(), members.size(), missing.isEmpty() && extraCount == 0,
					missing.subList(0, Math.min(DIFFERENCES_SHOWN, missing.size())), missing.size(), extra, extraCount,
					inactive, ecl.length() <= ValueSetDefinition.MAX_ECL_LENGTH, ValueSetDefinition.MAX_ECL_LENGTH);
		}

		private String text(Clause clause) {
			String root = (clause.operator().isEmpty() ? "" : clause.operator() + " ") + withTerm(clause.conceptId());
			if (clause.exceptions().isEmpty()) {
				return root;
			}
			List<String> exceptions = clause.exceptions().stream().map(this::text).toList();
			return "(" + root + " MINUS " + (exceptions.size() == 1 ? exceptions.get(0)
					: "(" + String.join(" OR ", exceptions) + ")") + ")";
		}

		private String withTerm(String conceptId) {
			String term = terms.get(conceptId);
			return term == null ? conceptId : conceptId + " |" + term.replace("|", "") + "|";
		}

		private long count(String ecl) {
			return snowstorm.expandLong(ecl, branch, 0, 1).total();
		}

		private long countMembersIn(String ecl) {
			return snowstorm.expandLong(ecl, branch, 0, 1, members).total();
		}

		private List<String> idsOfMembersIn(String ecl) {
			return ids(snowstorm.expandLong(ecl, branch, 0, ALL, members));
		}

		private void remember(Concept concept) {
			if (concept.preferredTerm() != null) {
				terms.putIfAbsent(concept.conceptId(), concept.preferredTerm());
			}
		}
	}

	private static List<String> ids(ExpansionPage page) {
		return page.items().stream().map(Concept::conceptId).toList();
	}

	private static String or(Collection<String> conceptIds) {
		return "(" + String.join(" OR ", conceptIds) + ")";
	}
}
