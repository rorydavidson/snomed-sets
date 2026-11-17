package org.snomed.valuesets.valueset;

import java.util.List;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * An ECL expression worked out to match a hand-picked list, checked by expanding it.
 *
 * @param codeSystemVersion the release the list is on, or null for a live branch
 * @param ecl the expression, one clause per line, with terms
 * @param structuredClauses how many clauses cover a part of the hierarchy rather than naming one concept
 * @param namedConcepts how many concepts the expression names one by one
 * @param conceptCount how many active concepts the list has
 * @param exact true when the expression matches exactly the active concepts in the list
 * @param missing some of the list's concepts the expression does not match; empty when exact
 * @param extra some of the concepts the expression matches that are not in the list; empty when exact
 * @param leftOut inactive concepts kept in the list, which no ECL expression can match
 * @param fitsDefinition false when the expression is longer than a value set definition may be
 */
public record EclSuggestion(String codeSystem, String codeSystemVersion, String branch, String ecl,
		int structuredClauses, int namedConcepts, int conceptCount, boolean exact, List<Concept> missing,
		long missingCount, List<Concept> extra, long extraCount, List<Concept> leftOut, boolean fitsDefinition,
		int maxLength) {
}
