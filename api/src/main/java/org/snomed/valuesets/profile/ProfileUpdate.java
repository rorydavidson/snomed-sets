package org.snomed.valuesets.profile;

import jakarta.validation.constraints.Size;

/**
 * @param organisation shown instead of the person's name on the value sets where they choose it.
 *        Left empty to have none.
 * @param contactable whether signed-in people may message them about their value sets
 */
public record ProfileUpdate(@Size(max = 120) String organisation, boolean contactable) {
}
