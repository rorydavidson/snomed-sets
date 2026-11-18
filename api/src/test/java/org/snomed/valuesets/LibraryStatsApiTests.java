package org.snomed.valuesets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.valueset.LibraryStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
class LibraryStatsApiTests {

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LibraryStatsService stats;

	@MockitoBean
	SnowstormClient snowstorm;

	@BeforeEach
	@AfterEach
	void startAfresh() {
		jdbc.update("DELETE FROM value_set");
		stats.refresh();
	}

	private long set(String type, String visibility, String status) {
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('A set', '', ?, '<< 195967001', 'MAIN/2026-01-01', ?, ?, 'olive', 'Olive', NOW(6), NOW(6))
				""", type, status, visibility);
		return jdbc.queryForObject("SELECT MAX(id) FROM value_set", Long.class);
	}

	/** A published version holding the given concepts. */
	private void publish(long setId, String... conceptIds) {
		jdbc.update("""
				INSERT INTO value_set_version (value_set_id, version_number, ecl, branch, concept_count, note,
				                               published_at, content_frozen)
				VALUES (?, 1, '', 'MAIN/2026-01-01', ?, '', NOW(6), TRUE)
				""", setId, conceptIds.length);
		for (String conceptId : conceptIds) {
			member(setId, 1, conceptId);
		}
	}

	private void member(long setId, int version, String conceptId) {
		jdbc.update("""
				INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
				                              preferred_term_language, fully_specified_name)
				VALUES (?, ?, ?, 'term', 'en', 'term')
				""", setId, version, conceptId);
	}

	@Test
	void eachSetSaysHowManyConceptsItHasAsPeopleAreShownIt() throws Exception {
		long published = set("ECL", "COMMUNITY", "PUBLISHED");
		publish(published, "195967001", "233678006", "389145006");
		long pickedDraft = set("MANUAL", "PRIVATE", "DRAFT");
		member(pickedDraft, 0, "195967001");
		member(pickedDraft, 0, "233678006");
		long eclDraft = set("ECL", "PRIVATE", "DRAFT");

		mvc.perform(get("/api/value-sets/" + published)).andExpect(jsonPath("$.conceptCount").value(3));
		var olive = oidcLogin().idToken(token -> token.subject("olive"));
		mvc.perform(get("/api/value-sets/" + pickedDraft).with(olive)).andExpect(jsonPath("$.conceptCount").value(2));
		// An ECL set never published has no stored list to count.
		mvc.perform(get("/api/value-sets/" + eclDraft).with(olive)).andExpect(jsonPath("$.conceptCount").doesNotExist());
	}

	@Test
	void countsPublishedCommunitySetsAndEachConceptOnce() throws Exception {
		long first = set("ECL", "COMMUNITY", "PUBLISHED");
		publish(first, "195967001", "233678006");
		long second = set("MANUAL", "COMMUNITY", "PUBLISHED");
		publish(second, "233678006", "389145006");
		// Neither a private set nor a draft is counted, or its existence would show.
		long privateSet = set("ECL", "PRIVATE", "PUBLISHED");
		publish(privateSet, "38341003");
		long draft = set("MANUAL", "COMMUNITY", "DRAFT");
		member(draft, 0, "22298006");

		mvc.perform(get("/api/library-stats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valueSets").value(2))
				.andExpect(jsonPath("$.concepts").value(3));
	}

	@Test
	void reportsAnEmptyLibraryAsNothing() throws Exception {
		mvc.perform(get("/api/library-stats"))
				.andExpect(jsonPath("$.valueSets").value(0))
				.andExpect(jsonPath("$.concepts").value(0));
	}
}
