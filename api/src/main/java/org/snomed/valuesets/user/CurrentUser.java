package org.snomed.valuesets.user;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The person making a request, however they signed in, or {@link #ANONYMOUS} for a visitor
 * who has not. Controllers take this as a parameter so they do not depend on Keycloak types.
 *
 * @param subject stable identifier used for ownership. Never sent to the browser. Null when anonymous.
 * @param name the name shown to other people. Never the email address. Null when anonymous.
 * @param email the account's verified email address, or null if it has none. Never sent to other
 *        people's browsers. It is only stored when the person lets others message them, and only
 *        used as the address messages go to, or as the reply-to of a message they send.
 */
public record CurrentUser(String subject, String name, String email) {

	/** A visitor who has not signed in. They may only read published community value sets. */
	public static final CurrentUser ANONYMOUS = new CurrentUser(null, null, null);

	public CurrentUser(String subject, String name) {
		this(subject, name, null);
	}

	public boolean isSignedIn() {
		return subject != null;
	}

	/**
	 * @param localLoginEmail the email address given to the development-only local user, if any
	 */
	static CurrentUser of(Authentication authentication, String localLoginEmail) {
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
			return ANONYMOUS;
		}
		Object principal = authentication.getPrincipal();
		if (principal instanceof OidcUser oidcUser) {
			// Only an address Keycloak has verified, so nobody can receive messages meant for someone else.
			String email = Boolean.TRUE.equals(oidcUser.getEmailVerified()) ? oidcUser.getEmail() : null;
			return new CurrentUser(oidcUser.getSubject(), displayName(oidcUser), email);
		}
		if (principal instanceof UserDetails localUser) {
			// Prefixed so a local development user can never match a Keycloak subject.
			return new CurrentUser("local:" + localUser.getUsername(), localUser.getUsername(),
					localLoginEmail == null || localLoginEmail.isBlank() ? null : localLoginEmail);
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
