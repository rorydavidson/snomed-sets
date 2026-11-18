package org.snomed.valuesets.profile;

import java.time.Instant;

/**
 * What a value set shows about its owner's profile.
 *
 * @param subject never sent to a browser; used to match profiles to value sets
 * @param contactable true while the owner lets signed-in people message them
 * @param avatarUpdatedAt null when they have no picture
 */
public record OwnerProfile(String subject, String publicId, String organisation, boolean contactable,
		Instant avatarUpdatedAt) {

	/** An owner who has not saved a profile. */
	public static OwnerProfile none(String subject) {
		return new OwnerProfile(subject, null, null, false, null);
	}

	public String avatarUrl() {
		return avatarUrl(publicId, avatarUpdatedAt);
	}

	/**
	 * Where a signed-in browser can fetch a picture, or null when there is none. The time is
	 * included so a new picture is not hidden by the old one in the browser's cache.
	 */
	static String avatarUrl(String publicId, Instant avatarUpdatedAt) {
		return avatarUpdatedAt == null ? null : "/api/avatars/" + publicId + "?v=" + avatarUpdatedAt.toEpochMilli();
	}
}
