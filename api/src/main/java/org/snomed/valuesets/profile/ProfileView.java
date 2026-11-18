package org.snomed.valuesets.profile;

import org.snomed.valuesets.user.CurrentUser;

/**
 * A person's own profile, as shown to them alone.
 *
 * @param accountEmail their own verified address, so they know where messages would go; null if
 *        the account has none. Only ever sent to that person.
 */
public record ProfileView(String name, String organisation, boolean contactable, String accountEmail,
		String avatarUrl) {

	static ProfileView of(UserProfile profile, CurrentUser user) {
		if (profile == null) {
			return new ProfileView(user.name(), null, false, user.email(), null);
		}
		return new ProfileView(user.name(), profile.getOrganisation(), profile.getContactEmail() != null,
				user.email(), profile.avatarUrl());
	}
}
