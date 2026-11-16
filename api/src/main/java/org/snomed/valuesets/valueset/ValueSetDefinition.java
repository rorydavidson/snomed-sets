package org.snomed.valuesets.valueset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.snowstorm.SnowstormClient;

/**
 * What a person sends when creating or editing a value set.
 *
 * The ECL is not checked against Snowstorm on save, so a draft can be kept while it is
 * still being worked on, or while Snowstorm is unavailable. It is checked on publishing.
 */
public record ValueSetDefinition(@NotBlank @Size(max = 200) String name,
		@NotNull @Size(max = 2000) String description,
		@NotBlank @Size(max = 4000) String ecl,
		@NotBlank @Size(max = 200) @Pattern(regexp = SnowstormClient.BRANCH_PATH_REGEX,
				message = "Branch path is not valid.") String branch,
		@NotNull ValueSet.Visibility visibility) {
}
