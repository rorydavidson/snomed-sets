package org.snomed.valuesets;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
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
class ConceptLanguageApiTests {

	private static final String NORWEGIAN_RELEASE = "MAIN/SNOMEDCT-NO/2026-01-01";

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

	/** A published set in the Norwegian Edition, whose stored list has US English terms. */
	@BeforeEach
	void aPublishedNorwegianSet() {
		given(snowstorm.editionFor(NORWEGIAN_RELEASE)).willReturn(new Edition("SNOMEDCT-NO", "Norwegian Edition", "no",
				new LinkedHashMap<>(Map.of("en", "English", "no", "Norwegian")), Map.of()));
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('Astma', '', 'MANUAL', '', ?, 'PUBLISHED', 'COMMUNITY', 'olive', 'Olive', NOW(6), NOW(6))
				""", NORWEGIAN_RELEASE);
		setId = jdbc.queryForObject("SELECT MAX(id) FROM value_set", Long.class);
		jdbc.update("""
				INSERT INTO value_set_version (value_set_id, version_number, ecl, branch, concept_count, note,
				                               published_at, content_frozen)
				VALUES (?, 1, '', ?, 2, '', NOW(6), TRUE)
				""", setId, NORWEGIAN_RELEASE);
		member("195967001", "Asthma");
		member("233678006", "Childhood asthma");
	}

	@AfterEach
	void removeEverythingMade() {
		jdbc.update("DELETE FROM value_set");
	}

	private void member(String conceptId, String term) {
		jdbc.update("""
				INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
				                              preferred_term_language, fully_specified_name)
				VALUES (?, 1, ?, ?, 'en', ?)
				""", setId, conceptId, term, term + " (disorder)");
	}

	@Test
	void offersTheEditionsLanguagesWithItsOwnFirstEvenToVisitors() throws Exception {
		mvc.perform(get("/api/value-sets/" + setId + "/languages"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.defaultLanguage").value("no"))
				.andExpect(jsonPath("$.available[0].code").value("no"))
				.andExpect(jsonPath("$.available[0].name").value("Norwegian"))
				.andExpect(jsonPath("$.available[1].code").value("en"));
	}

	@Test
	void fallsBackToEnglishWhenSnowstormCannotSayWhichLanguages() throws Exception {
		given(snowstorm.editionFor(anyString())).willThrow(new SnowstormException("down", false, null));
		mvc.perform(get("/api/value-sets/" + setId + "/languages"))
				.andExpect(jsonPath("$.defaultLanguage").value("en"))
				.andExpect(jsonPath("$.available.length()").value(1));
	}

	@Test
	void showsAStoredListInTheLanguageAskedForKeepingEnglishWhereThereIsNoTerm() throws Exception {
		// Only Asthma has a Norwegian term; Childhood asthma keeps its stored English one.
		given(snowstorm.activeConcepts(anyList(), eq(NORWEGIAN_RELEASE), eq("no")))
				.willReturn(new ExpansionPage(List.of(new ExpansionPage.Concept("195967001", "astma", "x", "no")), 1, 0, 2));

		mvc.perform(get("/api/value-sets/" + setId + "/expansion").param("language", "no"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].conceptId").value("195967001"))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("astma"))
				.andExpect(jsonPath("$.items[0].language").value("no"))
				.andExpect(jsonPath("$.items[0].fullySpecifiedName").value("Asthma (disorder)"))
				.andExpect(jsonPath("$.items[1].preferredTerm").value("Childhood asthma"))
				.andExpect(jsonPath("$.items[1].language").value("en"));
		mvc.perform(get("/api/value-sets/" + setId + "/versions/1/expansion").param("language", "no"))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("astma"));
	}

	@Test
	void keepsTheStoredTermsWhenSnowstormCannotBeReached() throws Exception {
		given(snowstorm.activeConcepts(anyList(), anyString(), anyString()))
				.willThrow(new SnowstormException("down", false, null));

		mvc.perform(get("/api/value-sets/" + setId + "/expansion").param("language", "no"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Asthma"));
	}

	@Test
	void showsTheStoredEnglishTermsWithoutAskingSnowstorm() throws Exception {
		mvc.perform(get("/api/value-sets/" + setId + "/expansion").param("language", "en"))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Asthma"));
		mvc.perform(get("/api/value-sets/" + setId + "/expansion"))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Asthma"));
	}

	@Test
	void expandsAnUnpublishedEclDraftInTheLanguageAskedFor() throws Exception {
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('Draft', '', 'ECL', '<< 195967001', ?, 'DRAFT', 'PRIVATE', 'olive', 'Olive', NOW(6), NOW(6))
				""", NORWEGIAN_RELEASE);
		long draft = jdbc.queryForObject("SELECT MAX(id) FROM value_set", Long.class);
		given(snowstorm.expand(eq("<< 195967001"), eq(NORWEGIAN_RELEASE), anyInt(), anyInt(), eq("no")))
				.willReturn(new ExpansionPage(List.of(new ExpansionPage.Concept("195967001", "astma", "x", "no")), 1, 0, 50));

		mvc.perform(get("/api/value-sets/" + draft + "/expansion").param("language", "no")
						.with(oidcLogin().idToken(token -> token.subject("olive"))))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("astma"));
	}

	@Test
	void theGlobalPatientSetDownloadIsAlwaysInUsEnglish() throws Exception {
		given(snowstorm.activeConcepts(anyList(), anyString(), anyString()))
				.willReturn(new ExpansionPage(List.of(new ExpansionPage.Concept("195967001", "astma", "x", "no")), 1, 0, 2));

		// Even when a language is asked for, it is not used: the layout is US English only.
		mvc.perform(get("/api/value-sets/" + setId + "/download").param("language", "no"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("195967001\tAsthma (disorder)\tAsthma\t")))
				.andExpect(content().string(not(containsString("astma"))));
		verify(snowstorm, never()).activeConcepts(anyList(), anyString(), anyString());
	}

	@Test
	void refusesALanguageThatIsNotALanguageCode() throws Exception {
		mvc.perform(get("/api/value-sets/" + setId + "/expansion").param("language", "no,en;q=1"))
				.andExpect(status().isBadRequest());
	}
}
