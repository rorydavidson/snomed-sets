package org.snomed.valuesets.valueset;

import java.time.Instant;
import java.util.List;

import org.snomed.valuesets.profile.OwnerProfile;
import org.snomed.valuesets.user.CurrentUser;

/**
 * What the browser is told about a value set.
 *
 * @param ecl empty for a hand-picked value set
 * @param codeSystemVersion the release of the code system the content comes from, for example
 *        2026-01-01, or null when it is read from the live branch
 * @param latestVersion the version in use: the newest one that is not deprecated, or the newest
 *        of all if every version is deprecated. Null if never published.
 * @param deprecated true when the owner has marked the whole value set as not to be used
 * @param allVersionsDeprecated true when the set has been published and every version is deprecated
 * @param unpublishedChanges true only for the owner, when the definition differs from the latest version
 * @param ownerName who the set is shown as made by: the owner's organisation when they chose that and
 *        have one, otherwise their name. Null for visitors, who are not told who made a set.
 * @param ownerIsOrganisation true when ownerName is an organisation rather than a person
 * @param ownerAvatarUrl the owner's picture, or null. Null for visitors.
 * @param ownerContactable true when the person asking may message the owner: they are signed in,
 *        not the owner, the owner allows it, and messaging is set up
 * @param showOrganisation the owner's choice for this set; only told to the owner, false for others
 * @param sctid the SNOMED CT reference set concept identifying the set, or null
 * @param sctidTerm that concept's fully specified name when it was checked
 */
public record ValueSetView(long id, ValueSet.DefinitionType type, String codeSystem, String name,
		String description, String ecl, String branch, String codeSystemVersion,
		ValueSet.Status status, ValueSet.Visibility visibility, String ownerName, boolean ownerIsOrganisation,
		String ownerAvatarUrl, boolean ownerContactable, boolean ownedByMe,
		Instant updatedAt, Integer latestVersion, boolean unpublishedChanges, List<String> tags,
		boolean deprecated, boolean allVersionsDeprecated, String deprecationReason, boolean showOrganisation,
		String sctid, String sctidTerm) {

	/**
	 * The owner sees the definition they are working on. Everyone else sees the latest
	 * published version, so a set they rely on does not change until its owner publishes again.
	 */
	static ValueSetView of(ValueSet valueSet, ValueSetVersion latest, boolean unpublishedChanges, CurrentUser user,
			OwnerProfile ownerProfile, boolean messagingEnabled) {
		boolean owner = valueSet.isOwnedBy(user.subject());
		boolean showPublished = latest != null && !owner;
		String branch = showPublished ? latest.getBranch() : valueSet.getBranch();
		boolean asOrganisation = valueSet.isShowOrganisation() && ownerProfile.organisation() != null;
		// People who have not signed in are not shown who made a set.
		boolean showOwner = user.isSignedIn();
		return new ValueSetView(valueSet.getId(), valueSet.getDefinitionType(), valueSet.getCodeSystem(),
				valueSet.getName(),
				valueSet.getDescription(),
				showPublished ? latest.getEcl() : valueSet.getEcl(),
				branch, ValueSet.releaseOf(branch),
				valueSet.getStatus(), valueSet.getVisibility(),
				showOwner ? (asOrganisation ? ownerProfile.organisation() : valueSet.getOwnerName()) : null,
				showOwner && asOrganisation,
				showOwner ? ownerProfile.avatarUrl() : null,
				showOwner && !owner && messagingEnabled && ownerProfile.contactable(),
				owner,
				valueSet.getUpdatedAt(), latest == null ? null : latest.getVersionNumber(),
				owner && unpublishedChanges, valueSet.getTags(), valueSet.isDeprecated(),
				// The version in use is only deprecated when there is no other to fall back to.
				latest != null && latest.isDeprecated(), valueSet.getDeprecationReason(),
				owner && valueSet.isShowOrganisation(), valueSet.getSctid(), valueSet.getSctidTerm());
	}

	/** @param frozen false for an old version whose concept list was not stored when it was published */
	public record VersionView(int number, String ecl, String branch, long conceptCount, String note,
			Instant publishedAt, boolean frozen, boolean deprecated, String deprecationReason) {

		static VersionView of(ValueSetVersion version) {
			return new VersionView(version.getVersionNumber(), version.getEcl(), version.getBranch(),
					version.getConceptCount(), version.getNote(), version.getPublishedAt(), version.isContentFrozen(),
					version.isDeprecated(), version.getDeprecationReason());
		}
	}
}
