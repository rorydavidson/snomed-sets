package org.snomed.valuesets.snowstorm;

import java.util.List;
import java.util.Map;

/**
 * What a release says about a concept that is no longer active.
 *
 * @param indicator the inactivation reason Snowstorm reports, for example DUPLICATE or OUTDATED; may be null
 * @param associationTargets keyed by historical association, for example REPLACED_BY or SAME_AS,
 *        each with the identifiers of the concepts it points to
 */
public record Inactivation(boolean active, String indicator, Map<String, List<String>> associationTargets) {
}
