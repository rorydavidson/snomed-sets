package org.snomed.valuesets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestKeycloakConfig.class)
@TestPropertySource(properties = { "KEYCLOAK_CLIENT_ID=test-client", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"KEYCLOAK_ISSUER_URI=http://keycloak.test" })
class VersionLabelApiTests {

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	private long setId;

	/** A hand-picked set with one concept in its working list, so publishing needs no Snowstorm. */
	@BeforeEach
	void aHandPickedSetOwnedByOlive() {
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('Asthma', '', 'MANUAL', '', 'MAIN/2026-01-01', 'DRAFT', 'COMMUNITY', 'olive', 'Olive', NOW(6), NOW(6))
				""");
		setId = jdbc.queryForObject("SELECT MAX(id) FROM value_set", Long.class);
		addToWorkingList("389145006", "Allergic asthma");
	}

	@AfterEach
	void removeEverythingMade() {
		jdbc.update("DELETE FROM value_set");
	}

	private void addToWorkingList(String conceptId, String term) {
		jdbc.update("""
				INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
				                              preferred_term_language, fully_specified_name)
				VALUES (?, 0, ?, ?, 'en', ?)
				""", setId, conceptId, term, term);
	}

	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private ResultActions publish(String label) throws Exception {
		String body = label == null ? "{\"note\": \"\"}" : "{\"note\": \"\", \"label\": \"" + label + "\"}";
		return mvc.perform(post("/api/value-sets/" + setId + "/versions").with(signedInAs("olive")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions relabel(int version, String label, String as) throws Exception {
		return mvc.perform(put("/api/value-sets/" + setId + "/versions/" + version + "/label")
				.with(signedInAs(as)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"" + label + "\"}"));
	}

	@Test
	void aVersionCanBeNamedWhenPublishedAndEveryoneSeesTheName() throws Exception {
		publish("  1 March   2026 ")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.number").value(1))
				.andExpect(jsonPath("$.label").value("1 March 2026"));

		mvc.perform(get("/api/value-sets/" + setId + "/versions"))
				.andExpect(jsonPath("$[0].label").value("1 March 2026"));
		mvc.perform(get("/api/value-sets/" + setId).with(signedInAs("rita")))
				.andExpect(jsonPath("$.latestVersion").value(1))
				.andExpect(jsonPath("$.latestVersionLabel").value("1 March 2026"));
	}

	@Test
	void aVersionPublishedWithoutANameKeepsItsNumberAndCanBeNamedLater() throws Exception {
		publish(null).andExpect(jsonPath("$.label").doesNotExist());

		relabel(1, "2026-03-01", "olive")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.label").value("2026-03-01"));
		relabel(1, "", "olive")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.label").doesNotExist());
	}

	@Test
	void twoVersionsOfASetCannotShareALabel() throws Exception {
		publish("2026-03-01").andExpect(status().isCreated());
		addToWorkingList("233678006", "Childhood asthma");

		publish("2026-03-01")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Version 1 is already called \"2026-03-01\"."));
		publish("Second release").andExpect(status().isCreated());
		relabel(2, "2026-03-01", "olive").andExpect(status().isConflict());
		// A version may keep its own name.
		relabel(1, "2026-03-01", "olive").andExpect(status().isOk());
	}

	@Test
	void refusesLabelsThatAreTooLongOrUseOtherCharacters() throws Exception {
		publish("<script>").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("letters, digits, spaces")));
		publish("x".repeat(41)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(containsString("at most 40")));
	}

	@Test
	void onlyTheOwnerCanNameAVersion() throws Exception {
		publish(null).andExpect(status().isCreated());
		relabel(1, "Mine now", "rita").andExpect(status().isForbidden());
	}
}
