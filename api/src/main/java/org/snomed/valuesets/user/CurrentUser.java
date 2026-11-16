package org.snomed.valuesets.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in person, however they signed in. Controllers take this as a
 * parameter so they do not depend on Keycloak types.
 *
 * @param subject stable identifier used for ownership. Never sent to the browser.
 * @param name the name shown to other people. Never the email address.
 */
public record CurrentUser(String subject, String name) {

	static CurrentUser of(Authentication authentication) {
		Object principal = authentication.getPrincipal();
		if (principal instanceof OidcUser oidcUser) {
			return new CurrentUser(oidcUser.getSubject(), displayName(oidcUser));
		}
		if (principal instanceof UserDetails localUser) {
			// Prefixed so a local development user can never match a Keycloak subject.
			return new CurrentUser("local:" + localUser.getUsername(), localUser.getUsername());
		}
		throw new IllegalStateException("Unsupported sign-in type: " + principal.getClass().getName());
	}

	private static String displayName(OidcUser user) {
		if (user.getFullName() != null) {
			return user.getFullName();
		}
		if (user.getPreferredUsername() != null) {
			return user.getPreferredUsername();
		}
		return "Unnamed user";
	}
}
