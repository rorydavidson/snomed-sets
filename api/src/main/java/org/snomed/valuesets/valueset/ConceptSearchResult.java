package org.snomed.valuesets.valueset;

import java.util.List;

/**
 * The value sets found by a concept search.
 *
 * @param truncated true when the words matched so many concepts that only the first of them were
 *        looked for in the sets, so more sets may hold matching concepts
 * @param storedTermsOnly true when Snowstorm could not be asked, so only the preferred terms and
 *        fully specified names stored with the lists were searched, not every description
 */
public record ConceptSearchResult(List<ConceptSearchHit> hits, boolean truncated, boolean storedTermsOnly) {
}
