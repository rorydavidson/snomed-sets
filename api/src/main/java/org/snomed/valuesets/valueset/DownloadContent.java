package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.Collection;

import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;

/**
 * What a download holds: one published version, or the owner's working draft.
 *
 * @param label the version number, or "draft" for the working draft
 * @param date when the version was published, or when the draft was last saved
 * @param status the FHIR status: active, retired for a deprecated set or version, or draft
 */
record DownloadContent(ValueSet valueSet, String label, String ecl, String branch, Instant date, String status,
		Collection<Concept> concepts) {

	static final String DRAFT = "draft";

	static DownloadContent of(ValueSet valueSet, ValueSetVersion version, Collection<Concept> concepts) {
		return new DownloadContent(valueSet, String.valueOf(version.getVersionNumber()), version.getEcl(),
				version.getBranch(), version.getPublishedAt(),
				valueSet.isDeprecated() || version.isDeprecated() ? "retired" : "active", concepts);
	}

	static DownloadContent draftOf(ValueSet valueSet, Collection<Concept> concepts) {
		return new DownloadContent(valueSet, DRAFT, valueSet.getEcl(), valueSet.getBranch(), valueSet.getUpdatedAt(),
				DRAFT, concepts);
	}

	/** For the file name: v3, or draft. */
	String fileLabel() {
		return DRAFT.equals(label) ? DRAFT : "v" + label;
	}
}
