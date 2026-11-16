package org.snomed.valuesets.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

	/** Only the display name is sent to the browser; no email, subject or token claims. */
	@GetMapping("/api/me")
	public Me me(@AuthenticationPrincipal OidcUser user) {
		String name = user.getFullName() != null ? user.getFullName() : user.getPreferredUsername();
		return new Me(name);
	}

	public record Me(String name) {
	}
}
