package org.snomed.valuesets.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Development only. Replaces Keycloak with one local user so the application can be
 * run and tested without an identity provider. It is off unless the "local-login"
 * profile is switched on, and there is no default username or password.
 */
@Configuration
@Profile(LocalLoginConfig.PROFILE)
class LocalLoginConfig {

	static final String PROFILE = "local-login";

	private static final Logger log = LoggerFactory.getLogger(LocalLoginConfig.class);

	@Bean
	UserDetailsService localUsers(@Value("${local-login.username}") String username,
			@Value("${local-login.password}") String password) {
		if (username.isBlank() || password.isBlank()) {
			throw new IllegalStateException(
					"The local-login profile needs LOCAL_LOGIN_USERNAME and LOCAL_LOGIN_PASSWORD to be set.");
		}
		log.warn("Local development sign-in is ON and Keycloak is OFF. Never use this profile on a shared or deployed environment.");
		return new InMemoryUserDetailsManager(User.withUsername(username)
				.password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password))
				.build());
	}
}
