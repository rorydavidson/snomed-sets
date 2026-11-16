package org.snomed.valuesets.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The frontend signs out with a background request, which cannot follow a
 * redirect to Keycloak. So instead of redirecting, this returns the Keycloak
 * end-session address as plain text for the frontend to navigate to.
 */
class SpaLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

	SpaLogoutSuccessHandler(ClientRegistrationRepository clientRegistrations) {
		super(clientRegistrations);
		setPostLogoutRedirectUri("{baseUrl}/");
	}

	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(MediaType.TEXT_PLAIN_VALUE);
		response.getWriter().write(determineTargetUrl(request, response, authentication));
	}
}
