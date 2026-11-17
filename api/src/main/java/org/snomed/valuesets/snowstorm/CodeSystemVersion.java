package org.snomed.valuesets.snowstorm;

/**
 * One release of a code system.
 *
 * @param version the release date, for example 2026-01-01
 * @param branchPath the branch the release is frozen on, for example MAIN/2026-01-01
 */
public record CodeSystemVersion(String version, String branchPath) {
}
