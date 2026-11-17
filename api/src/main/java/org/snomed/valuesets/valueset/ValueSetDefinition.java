package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * @param tags free-text labels, at most ten
 * @param codeSystemVersion the release to build a new value set on, for example 2026-01-01; the
 *        latest when left out. Ignored when editing: a value set moves to another release only by an upgrade.
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
		@Size(max = MAX_MEMBERS) List<@NotNull @Pattern(regexp = CONCEPT_ID_REGEX) String> conceptIds,
		@Size(max = MAX_TAGS) List<@Pattern(regexp = TAG_REGEX) String> tags,
		@Pattern(regexp = RELEASE_REGEX) String codeSystemVersion) {

	public static final String INTERNATIONAL_EDITION = "SNOMEDCT";
	public static final int MAX_MEMBERS = 2000;
	public static final String CONCEPT_ID_REGEX = "\\d{6,18}";
	public static final String RELEASE_REGEX = "\\d{4}-\\d{2}-\\d{2}";
	public static final int MAX_TAGS = 10;
	/** Letters, digits, spaces, hyphens and underscores, starting with a letter or digit. At most 40 characters. */
	public static final String TAG_REGEX = "[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,39}";

	public ValueSetDefinition {
		type = type == null ? ValueSet.DefinitionType.ECL : type;
		codeSystem = codeSystem == null ? INTERNATIONAL_EDITION : codeSystem;
		ecl = ecl == null ? "" : ecl;
		conceptIds = conceptIds == null ? List.of() : conceptIds;
		// Lower case and single spaces, so the same tag typed two ways is stored once.
		tags = tags == null ? List.of() : tags.stream()
				.filter(Objects::nonNull)
				.map(tag -> tag.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
				.filter(tag -> !tag.isEmpty())
				.distinct()
				.toList();
	}
}
