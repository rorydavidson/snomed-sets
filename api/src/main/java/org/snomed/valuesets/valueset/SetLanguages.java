package org.snomed.valuesets.valueset;

import java.util.List;

/**
 * The languages a set's concepts can be shown in.
 *
 * @param defaultLanguage the edition's own language, for example "no" for the Norwegian Edition, or "en"
 * @param available every language the edition holds, the default first
 */
public record SetLanguages(String defaultLanguage, List<Language> available) {

	/** @param name Snowstorm's name for it, for example "Norwegian" */
	public record Language(String code, String name) {
	}
}
