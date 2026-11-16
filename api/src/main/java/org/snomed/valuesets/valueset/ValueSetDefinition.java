package org.snomed.valuesets.valueset;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a person sends when creating or editing a value set.
 *
 * The ECL is not checked against Snowstorm on save, so a draft can be kept while it is
 * still being worked on, or while Snowstorm is unavailable. It is checked on publishing.
 *
 * @param type ECL when left out
 * @param codeSystem short name of a Snowstorm code system; the International Edition when left out.
 *        Like the type, it cannot be changed once the value set exists.
 * @param ecl the definition of an ECL value set; ignored for a hand-picked one
 * @param conceptIds the full list of a hand-picked value set; ignored for an ECL one.
 *        Only identifiers are accepted. The terms are looked up in Snowstorm, so a
 *        person cannot attach a made-up term to a concept.
 */
public record ValueSetDefinition(ValueSet.DefinitionType type,
		@NotBlank @Size(max = 200) String name,
		@NotNull @Size(max = 2000) String description,
		@Size(max = 4000) String ecl,
		@Size(max = 100) String codeSystem,
		@NotNull ValueSet.Visibility visibility,
		@Size(max = MAX_MEMBERS) List<@NotNull @Pattern(regexp = CONCEPT_ID_REGEX) String> conceptIds) {

	public static final String INTERNATIONAL_EDITION = "SNOMEDCT";
	public static final int MAX_MEMBERS = 2000;
	public static final String CONCEPT_ID_REGEX = "\\d{6,18}";

	public ValueSetDefinition {
		type = type == null ? ValueSet.DefinitionType.ECL : type;
		codeSystem = codeSystem == null ? INTERNATIONAL_EDITION : codeSystem;
		ecl = ecl == null ? "" : ecl;
		conceptIds = conceptIds == null ? List.of() : conceptIds;
	}
}
