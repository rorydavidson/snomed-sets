package org.snomed.valuesets.snowstorm;

/**
 * A SNOMED CT edition or extension held by Snowstorm.
 *
 * @param shortName for example SNOMEDCT or SNOMEDCT-BE
 * @param name a readable name; the short name when Snowstorm has none
 * @param branchPath the branch its content is on
 * @param latestVersion the newest release, for example 2026-01-01, or null if none
 * @param latestVersionBranch the branch that release is frozen on, for example MAIN/2026-01-01, or null if none
 */
public record CodeSystem(String shortName, String name, String branchPath, String latestVersion,
		String latestVersionBranch) {
}
