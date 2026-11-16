package org.snomed.valuesets;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/** The development-only sign-in that replaces Keycloak when the local-login profile is on. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({ "test", "local-login" })
class LocalLoginTests {

	private static final String USERNAME = "dev-user";
	// Made up for each run, so no password is written down anywhere.
	private static final String PASSWORD = UUID.randomUUID().toString();

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@DynamicPropertySource
	static void localUser(DynamicPropertyRegistry registry) {
		registry.add("LOCAL_LOGIN_USERNAME", () -> USERNAME);
		registry.add("LOCAL_LOGIN_PASSWORD", () -> PASSWORD);
	}

	@Autowired
	MockMvc mvc;

	@MockitoBean
	SnowstormClient snowstorm;

	private MockHttpSession signIn() throws Exception {
		return (MockHttpSession) mvc.perform(formLogin().user(USERNAME).password(PASSWORD))
				.andExpect(authenticated())
				.andExpect(redirectedUrl("/"))
				.andReturn().getRequest().getSession();
	}

	@Test
	void signInLinkSendsASignedOutBrowserToTheLocalForm() throws Exception {
		mvc.perform(get("/sign-in").accept(MediaType.TEXT_HTML))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", endsWith("/login")));
	}

	@Test
	void wrongPasswordIsRefused() throws Exception {
		mvc.perform(formLogin().user(USERNAME).password(PASSWORD + "x")).andExpect(unauthenticated());
	}

	@Test
	void apiStillRequiresSignIn() throws Exception {
		mvc.perform(get("/api/value-sets")).andExpect(status().isUnauthorized());
	}

	@Test
	void localUserCanUseTheApiAndOwnsWhatTheyCreate() throws Exception {
		MockHttpSession session = signIn();
		org.mockito.BDDMockito.given(snowstorm.codeSystems()).willReturn(java.util.List.of(
				new org.snomed.valuesets.snowstorm.CodeSystem("SNOMEDCT", "International Edition", "MAIN", null)));

		mvc.perform(get("/api/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(USERNAME));

		mvc.perform(post("/api/value-sets").session(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name": "Local set", "description": "", "ecl": "<< 195967001",
						 "branch": "MAIN", "visibility": "PRIVATE"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerName").value(USERNAME))
				.andExpect(jsonPath("$.ownedByMe").value(true));
	}

	@Test
	void signOutEndsTheSessionAndReturnsWhereToGoNext() throws Exception {
		MockHttpSession session = signIn();

		mvc.perform(post("/logout").session(session).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string("/"));
		mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
	}
}
