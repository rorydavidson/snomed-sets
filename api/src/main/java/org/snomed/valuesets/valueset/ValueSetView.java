package org.snomed.valuesets.valueset;

import java.time.Instant;

import org.snomed.valuesets.user.CurrentUser;

/**
 * What the browser is told about a value set.
 *
 * @param ecl empty for a hand-picked value set
 * @param latestVersion the newest published version number, or null if never published
 * @param unpublishedChanges true only for the owner, when the definition differs from the latest version
 */
public record ValueSetView(long id, ValueSet.DefinitionType type, String codeSystem, String name,
		String description, String ecl, String branch,
		ValueSet.Status status, ValueSet.Visibility visibility, String ownerName, boolean ownedByMe,
		Instant updatedAt, Integer latestVersion, boolean unpublishedChanges) {

	/**
	 * The owner sees the definition they are working on. Everyone else sees the latest
	 * published version, so a set they rely on does not change until its owner publishes again.
	 */
	static ValueSetView of(ValueSet valueSet, ValueSetVersion latest, boolean unpublishedChanges, CurrentUser user) {
		boolean owner = valueSet.isOwnedBy(user.subject());
		boolean showPublished = latest != null && !owner;
		return new ValueSetView(valueSet.getId(), valueSet.getDefinitionType(), valueSet.getCodeSystem(),
				valueSet.getName(),
				valueSet.getDescription(),
				showPublished ? latest.getEcl() : valueSet.getEcl(),
				showPublished ? latest.getBranch() : valueSet.getBranch(),
				valueSet.getStatus(), valueSet.getVisibility(), valueSet.getOwnerName(), owner,
				valueSet.getUpdatedAt(), latest == null ? null : latest.getVersionNumber(),
				owner && unpublishedChanges);
	}

	/** @param frozen false for an old version whose concept list was not stored when it was published */
	public record VersionView(int number, String ecl, String branch, long conceptCount, String note,
			Instant publishedAt, boolean frozen) {

		static VersionView of(ValueSetVersion version) {
			return new VersionView(version.getVersionNumber(), version.getEcl(), version.getBranch(),
					version.getConceptCount(), version.getNote(), version.getPublishedAt(), version.isContentFrozen());
		}
	}
}
