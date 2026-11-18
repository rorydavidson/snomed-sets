package org.snomed.valuesets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
// The visitors' limit is held in memory, so each test starts with a fresh application.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConceptSearchApiTests {

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	/** Unless a test says otherwise, Snowstorm finds nothing. */
	@BeforeEach
	void snowstormFindsNothing() {
		given(snowstorm.conceptsWithDescriptionMatching(anyString(), anyString(), anyList(), anyInt()))
				.willReturn(new ExpansionPage(List.of(), 0, 0, 10_000));
	}

	/** As Snowstorm answers: these concepts have a description matching the words. */
	private void snowstormMatches(String term, String... conceptIds) {
		given(snowstorm.conceptsWithDescriptionMatching(eq(term), eq("MAIN/2026-01-01"), anyList(), anyInt()))
				.willReturn(new ExpansionPage(Arrays.stream(conceptIds)
						.map(id -> new ExpansionPage.Concept(id, "term of " + id, null, "en")).toList(),
						conceptIds.length, 0, 10_000));
	}

	@AfterEach
	void removeEverythingMade() {
		jdbc.update("DELETE FROM value_set");
	}

	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private long set(String owner, String type, String visibility, String status) {
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('A set', '', ?, '', 'MAIN/2026-01-01', ?, ?, ?, ?, NOW(6), NOW(6))
				""", type, status, visibility, owner, "Person " + owner);
		return jdbc.queryForObject("SELECT MAX(id) FROM value_set", Long.class);
	}

	private void version(long setId, int number, boolean deprecated) {
		jdbc.update("""
				INSERT INTO value_set_version (value_set_id, version_number, ecl, branch, concept_count, note,
				                               published_at, content_frozen, deprecated_at)
				VALUES (?, ?, '', 'MAIN/2026-01-01', 0, '', NOW(6), TRUE, ?)
				""", setId, number, deprecated ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
	}

	private void member(long setId, int version, String conceptId, String term) {
		jdbc.update("""
				INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
				                              preferred_term_language, fully_specified_name)
				VALUES (?, ?, ?, ?, 'en', ?)
				""", setId, version, conceptId, term, term + " (disorder)");
	}

	@Test
	void findsSetsHoldingConceptsWhoseDescriptionsSnowstormMatches() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		member(published, 1, "22298006", "Myocardial infarction");
		member(published, 1, "38341003", "Hypertensive disorder");
		// "Heart attack" is a synonym, so the stored preferred term alone would not match.
		snowstormMatches("heart att", "22298006", "57054005");

		mvc.perform(get("/api/concept-search").param("term", "heart att").with(signedInAs("rita")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hits", hasSize(1)))
				.andExpect(jsonPath("$.hits[0].valueSetId").value(published))
				.andExpect(jsonPath("$.hits[0].matchCount").value(1))
				.andExpect(jsonPath("$.hits[0].examples[0].term").value("Myocardial infarction"))
				.andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.storedTermsOnly").value(false));
		verify(snowstorm).conceptsWithDescriptionMatching(eq("heart att"), eq("MAIN/2026-01-01"), eq(List.of("en")),
				eq(10_000));
	}

	@Test
	void searchesEveryLanguageOfTheEdition() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		given(snowstorm.editionFor("MAIN/2026-01-01")).willReturn(new Edition("SNOMEDCT-BE", "Belgian Edition", "fr",
				new LinkedHashMap<>(Map.of("fr", "French")), Map.of()));

		mvc.perform(get("/api/concept-search").param("term", "asthme").with(signedInAs("rita")))
				.andExpect(status().isOk());
		verify(snowstorm).conceptsWithDescriptionMatching(eq("asthme"), eq("MAIN/2026-01-01"), eq(List.of("fr", "en")),
				anyInt());
	}

	@Test
	void saysWhenTheWordsMatchedTooManyConceptsToCheckThemAll() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		member(published, 1, "38341003", "Hypertensive disorder");
		given(snowstorm.conceptsWithDescriptionMatching(eq("disorder"), anyString(), anyList(), anyInt()))
				.willReturn(new ExpansionPage(List.of(new ExpansionPage.Concept("38341003", "x", null, "en")), 134_114, 0, 10_000));

		mvc.perform(get("/api/concept-search").param("term", "disorder").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits", hasSize(1)))
				.andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	void fallsBackToStoredTermsWhenSnowstormCannotBeAsked() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		member(published, 1, "389145006", "Allergic asthma");
		given(snowstorm.conceptsWithDescriptionMatching(anyString(), anyString(), anyList(), anyInt()))
				.willThrow(new SnowstormException("down", false, null));

		mvc.perform(get("/api/concept-search").param("term", "ASTHMA allerg").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits[0].valueSetId").value(published))
				.andExpect(jsonPath("$.storedTermsOnly").value(true));
		mvc.perform(get("/api/concept-search").param("term", "%%%").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits", hasSize(0)));
	}

	@Test
	void findsAConceptByItsIdWithoutAskingSnowstorm() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		member(published, 1, "38341003", "Hypertensive disorder");

		mvc.perform(get("/api/concept-search").param("term", "38341003").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits[0].examples[0].term").value("Hypertensive disorder"));
		verify(snowstorm, never()).conceptsWithDescriptionMatching(anyString(), anyString(), anyList(), anyInt());
	}

	@Test
	void searchesTheVersionInUseNotAnOlderOne() throws Exception {
		long id = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(id, 1, false);
		member(id, 1, "389145006", "Allergic asthma");
		version(id, 2, false);
		member(id, 2, "38341003", "Hypertensive disorder");
		snowstormMatches("asthma", "389145006");

		mvc.perform(get("/api/concept-search").param("term", "asthma").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits", hasSize(0)));
	}

	@Test
	void keepsOtherPeoplesPrivateSetsOutButSearchesTheOwnersHandPickedDraft() throws Exception {
		long privateDraft = set("olive", "MANUAL", "PRIVATE", "DRAFT");
		member(privateDraft, 0, "389145006", "Allergic asthma");
		snowstormMatches("asthma", "389145006");

		mvc.perform(get("/api/concept-search").param("term", "asthma").with(signedInAs("rita")))
				.andExpect(jsonPath("$.hits", hasSize(0)));
		mvc.perform(get("/api/concept-search").param("term", "asthma").with(signedInAs("olive")))
				.andExpect(jsonPath("$.hits[0].valueSetId").value(privateDraft));
	}

	@Test
	void refusesASearchTooShortWithoutUsingUpAVisitorsTurn() throws Exception {
		mvc.perform(get("/api/concept-search").param("term", "as")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/concept-search").param("term", "asthma")).andExpect(status().isOk());
	}

	@Test
	void letsAVisitorSearchOnlyPublishedSetsAndOnceEveryThreeMinutes() throws Exception {
		long published = set("olive", "ECL", "COMMUNITY", "PUBLISHED");
		version(published, 1, false);
		member(published, 1, "389145006", "Allergic asthma");
		long draft = set("olive", "MANUAL", "COMMUNITY", "DRAFT");
		member(draft, 0, "233678006", "Childhood asthma");
		snowstormMatches("asthma", "389145006", "233678006");

		mvc.perform(get("/api/concept-search").param("term", "asthma"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hits", hasSize(1)))
				.andExpect(jsonPath("$.hits[0].valueSetId").value(published));
		mvc.perform(get("/api/concept-search").param("term", "asthma"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.detail").value(containsString("once every 3 minutes")));
		// Signed-in people are not limited.
		mvc.perform(get("/api/concept-search").param("term", "asthma").with(signedInAs("rita")))
				.andExpect(status().isOk());
	}
}
