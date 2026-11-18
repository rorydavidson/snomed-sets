package org.snomed.valuesets.valueset;

/**
 * How much the community has shared, for the landing page. Only published sets shared with the
 * community are counted, so private sets and drafts are not revealed.
 *
 * @param valueSets published community value sets
 * @param concepts different concepts across their concept lists, each counted once however many sets hold it
 */
public record LibraryStats(long valueSets, long concepts) {
}
