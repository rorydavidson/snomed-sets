package org.snomed.valuesets.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The browser never holds tokens. Sign-in happens here, on the server, and the
 * browser is given only an HTTP-only session cookie.
 */
@Configuration
public class SecurityConfig {

	/**
	 * What a visitor may read without signing in. The service narrows these to published
	 * community value sets, and the download to the Global Patient Set layout.
	 */
	private static final String[] PUBLIC_READS = {
		"/api/value-sets",
		"/api/value-sets/{id}",
		"/api/value-sets/sct/{sctid}",
		"/api/value-sets/{id}/expansion",
		"/api/value-sets/{id}/languages",
		"/api/value-sets/{id}/versions",
		"/api/value-sets/{id}/versions/{number}/expansion",
		"/api/value-sets/{id}/download",
		"/api/value-sets/{id}/saved-ecl",
		"/api/site",
		// Limited for visitors by AnonymousSearchLimit.
		"/api/concept-search",
	};

	/** The normal sign-in: SNOMED International accounts through Keycloak. */
	@Bean
	@Profile("!" + LocalLoginConfig.PROFILE)
	SecurityFilterChain keycloakSecurity(HttpSecurity http, ClientRegistrationRepository clientRegistrations)
			throws Exception {
		applySharedRules(http);
		http
			// Always land on the app root after sign-in, never on a saved API request.
			.oauth2Login(login -> login.defaultSuccessUrl("/", true))
			.logout(logout -> logout.logoutSuccessHandler(new SpaLogoutSuccessHandler(clientRegistrations)));
		return http.build();
	}

	/** Development only: a username and password form instead of Keycloak. */
	@Bean
	@Profile(LocalLoginConfig.PROFILE)
	SecurityFilterChain localLoginSecurity(HttpSecurity http) throws Exception {
		applySharedRules(http);
		http
			.formLogin(form -> form.defaultSuccessUrl("/", true))
			// Same contract as the Keycloak sign-out: reply with the address to go to next.
			.logout(logout -> logout.logoutSuccessHandler((request, response, authentication) -> {
				response.setStatus(HttpStatus.OK.value());
				response.setContentType(MediaType.TEXT_PLAIN_VALUE);
				response.getWriter().write("/");
			}));
		return http.build();
	}

	private static void applySharedRules(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
				.anyRequest().authenticated())
			// Cookie-based CSRF token that the Angular HttpClient echoes back as a header.
			.csrf(csrf -> csrf.spa())
			// API calls get a 401 for the frontend to handle, not a redirect to the sign-in page.
			.exceptionHandling(handling -> handling.defaultAuthenticationEntryPointFor(
				new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
				PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
	}
}
