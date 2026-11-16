package org.snomed.valuesets;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Replaces the Keycloak registration that is normally discovered from the issuer,
 * so tests start without any network call to an identity provider.
 */
@TestConfiguration
public class TestKeycloakConfig {

	@Bean
	ClientRegistrationRepository clientRegistrationRepository() {
		ClientRegistration keycloak = ClientRegistration.withRegistrationId("keycloak")
				.clientId("test-client")
				.clientSecret("test-secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope("openid", "profile")
				.authorizationUri("http://keycloak.test/auth")
				.tokenUri("http://keycloak.test/token")
				.jwkSetUri("http://keycloak.test/certs")
				.userInfoUri("http://keycloak.test/userinfo")
				.userNameAttributeName("preferred_username")
				.build();
		return new InMemoryClientRegistrationRepository(keycloak);
	}
}
