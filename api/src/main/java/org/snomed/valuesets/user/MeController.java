package org.snomed.valuesets.user;

import java.net.URI;

import org.snomed.valuesets.profile.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

	private final ProfileService profiles;

	public MeController(ProfileService profiles) {
		this.profiles = profiles;
	}

	/** Only the display name and picture are sent to the browser; no email, subject or token claims. */
	@GetMapping("/api/me")
	public Me me(CurrentUser user) {
		return new Me(user.name(), profiles.ownerProfile(user.subject()).avatarUrl());
	}

	/**
	 * The frontend's sign-in link. It needs a session, so a signed-out browser is first
	 * sent to whichever sign-in is configured (Keycloak, or the local development form).
	 */
	@GetMapping("/sign-in")
	public ResponseEntity<Void> signIn() {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/")).build();
	}

	/** @param avatarUrl null when they have no picture */
	public record Me(String name, String avatarUrl) {
	}
}
