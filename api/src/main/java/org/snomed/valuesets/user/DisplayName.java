package org.snomed.valuesets.user;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class DisplayName {

	private DisplayName() {
	}

	/** The name shown to other people. Never the email address or the Keycloak subject. */
	public static String of(OidcUser user) {
		if (user.getFullName() != null) {
			return user.getFullName();
		}
		if (user.getPreferredUsername() != null) {
			return user.getPreferredUsername();
		}
		return "Unnamed user";
	}
}
