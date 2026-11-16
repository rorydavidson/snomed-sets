package org.snomed.valuesets.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The browser never holds tokens. Keycloak sign-in happens here, on the server,
 * and the browser is given only an HTTP-only session cookie.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrations)
			throws Exception {
		OidcClientInitiatedLogoutSuccessHandler logoutHandler =
				new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
		logoutHandler.setPostLogoutRedirectUri("{baseUrl}/");

		http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			// Always land on the app root after sign-in, never on a saved API request.
			.oauth2Login(login -> login.defaultSuccessUrl("/", true))
			.logout(logout -> logout.logoutSuccessHandler(logoutHandler))
			// Cookie-based CSRF token that the Angular HttpClient echoes back as a header.
			.csrf(csrf -> csrf.spa())
			// API calls get a 401 for the frontend to handle, not a redirect to Keycloak.
			.exceptionHandling(handling -> handling.defaultAuthenticationEntryPointFor(
				new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
				PathPatternRequestMatcher.withDefaults().matcher("/api/**")));

		return http.build();
	}
}
