package org.snomed.valuesets.snowstorm;

import java.util.Map;

/**
 * The SNOMED CT edition a branch belongs to, as far as showing a concept needs it.
 *
 * @param language the language its terms are shown in: the edition's own when it has one besides
 *        English, for example "no" for the Norwegian Edition, otherwise "en"
 * @param languages the languages it holds, by code, with Snowstorm's names for them
 * @param moduleOwners each module the edition holds, with the module that owns it. International
 *        Edition modules are owned by the model component module.
 */
public record Edition(String shortName, String name, String language, Map<String, String> languages,
		Map<String, String> moduleOwners) {

	static final String MODEL_MODULE = "900000000000012004";

	/** True for a module of the International Edition rather than of a national extension. */
	public boolean isInternational(String moduleId) {
		return MODEL_MODULE.equals(moduleId) || MODEL_MODULE.equals(moduleOwners.get(moduleId));
	}
}
