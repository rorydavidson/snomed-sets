package org.snomed.valuesets.valueset;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * A published version as a FHIR R4 ValueSet resource.
 *
 * The compose holds the definition: the ECL expression as a SNOMED CT "constraint" filter, or
 * the hand-picked concepts. The expansion holds the concept list stored when the version was
 * published, so the file says exactly what the version contains. The owner's name is left out,
 * as the file may travel beyond the people who can see it here.
 *
 * @param fileName a safe name to offer the browser
 * @param resource the resource, as nested maps ready to be written as JSON. FHIR does not allow
 *        empty or null values, so fields without a value are left out.
 */
public record FhirValueSet(String fileName, Map<String, Object> resource) {

	public static final String MEDIA_TYPE = "application/fhir+json";
	static final String SNOMED_SYSTEM = "http://snomed.info/sct";

	static FhirValueSet of(ValueSet valueSet, ValueSetVersion version, Collection<Concept> concepts, String baseUrl,
			String editionModuleId) {
		String snomedVersion = snomedVersion(editionModuleId, ValueSet.releaseOf(version.getBranch()));

		Map<String, Object> resource = new LinkedHashMap<>();
		resource.put("resourceType", "ValueSet");
		resource.put("id", String.valueOf(valueSet.getId()));
		resource.put("url", trimSlash(baseUrl) + "/value-sets/" + valueSet.getId());
		resource.put("version", String.valueOf(version.getVersionNumber()));
		resource.put("name", computableName(valueSet.getName()));
		resource.put("title", valueSet.getName());
		resource.put("status", valueSet.isDeprecated() || version.isDeprecated() ? "retired" : "active");
		resource.put("experimental", false);
		resource.put("date", version.getPublishedAt().toString());
		putIfPresent(resource, "description", valueSet.getDescription());
		resource.put("compose", Map.of("include", List.of(include(valueSet, version, concepts, snomedVersion))));
		resource.put("expansion", expansion(version, concepts, snomedVersion));
		return new FhirValueSet(GpsFile.fileNameFor(valueSet.getName(), version.getVersionNumber(), "json"), resource);
	}

	/**
	 * The SNOMED CT edition and release, written as FHIR expects, for example
	 * http://snomed.info/sct/900000000000207008/version/20260101. Null for a version built on
	 * the live branch, or when the edition's module is not known.
	 */
	static String snomedVersion(String editionModuleId, String release) {
		if (editionModuleId == null || release == null) {
			return null;
		}
		return SNOMED_SYSTEM + "/" + editionModuleId + "/version/" + release.replace("-", "");
	}

	private static Map<String, Object> include(ValueSet valueSet, ValueSetVersion version, Collection<Concept> concepts,
			String snomedVersion) {
		Map<String, Object> include = new LinkedHashMap<>();
		include.put("system", SNOMED_SYSTEM);
		putIfPresent(include, "version", snomedVersion);
		if (valueSet.isManual()) {
			include.put("concept", concepts.stream().map(concept -> {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("code", concept.conceptId());
				putIfPresent(entry, "display", concept.preferredTerm());
				return entry;
			}).toList());
		}
		else {
			include.put("filter", List.of(Map.of("property", "constraint", "op", "=", "value", version.getEcl())));
		}
		return include;
	}

	private static Map<String, Object> expansion(ValueSetVersion version, Collection<Concept> concepts,
			String snomedVersion) {
		Map<String, Object> expansion = new LinkedHashMap<>();
		expansion.put("timestamp", version.getPublishedAt().toString());
		expansion.put("total", concepts.size());
		List<Map<String, Object>> contains = new ArrayList<>();
		for (Concept concept : concepts) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("system", SNOMED_SYSTEM);
			putIfPresent(entry, "version", snomedVersion);
			if (!concept.active()) {
				// Kept in a hand-picked list after the release made it inactive.
				entry.put("inactive", true);
			}
			entry.put("code", concept.conceptId());
			putIfPresent(entry, "display", concept.preferredTerm());
			contains.add(entry);
		}
		if (!contains.isEmpty()) {
			expansion.put("contains", contains);
		}
		return expansion;
	}

	/**
	 * A name a computer can use, as FHIR recommends: letters, digits and underscores, starting
	 * with a capital letter. "Childhood asthma (v2)" becomes ChildhoodAsthmaV2.
	 */
	static String computableName(String title) {
		StringBuilder name = new StringBuilder();
		for (String word : Normalizer.normalize(title, Normalizer.Form.NFKD).split("[^A-Za-z0-9]+")) {
			if (!word.isEmpty()) {
				name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
			}
		}
		String result = name.length() > 255 ? name.substring(0, 255) : name.toString();
		return result.isEmpty() || !Character.isLetter(result.charAt(0)) ? "ValueSet" + result : result;
	}

	private static void putIfPresent(Map<String, Object> map, String key, String value) {
		if (value != null && !value.isBlank()) {
			map.put(key, value);
		}
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
