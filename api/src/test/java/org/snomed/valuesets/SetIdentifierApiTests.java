package org.snomed.valuesets;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.snomed.valuesets.snowstorm.SnowstormException;
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
class SetIdentifierApiTests {

	private static final String RELEASE = "MAIN/2026-01-01";
	/** A reference set concept, with a valid check digit. */
	private static final String REFSET = "723264001";
	private static final String REFSET_FSN = "Lateralizable body structure reference set (foundation metadata concept)";
	/** A real concept that is not a reference set. */
	private static final String NOT_A_REFSET = "22298006";

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	@BeforeEach
	void snowstormKnowsOneReferenceSet() {
		given(snowstorm.codeSystems()).willReturn(List.of(
				new CodeSystem("SNOMEDCT", "International Edition", "MAIN", "2026-01-01", RELEASE)));
		given(snowstorm.expand(anyString(), anyString(), anyInt(), anyInt()))
				.willReturn(new ExpansionPage(List.of(), 0, 0, 1));
		given(snowstorm.expand(eq(REFSET + " AND << 900000000000455006"), eq(RELEASE), anyInt(), anyInt()))
				.willReturn(page(REFSET, REFSET_FSN));
		given(snowstorm.expand(eq(NOT_A_REFSET), eq(RELEASE), anyInt(), anyInt()))
				.willReturn(page(NOT_A_REFSET, "Myocardial infarction (disorder)"));
	}

	@AfterEach
	void removeEverythingMade() {
		jdbc.update("DELETE FROM value_set");
	}

	private static ExpansionPage page(String conceptId, String fsn) {
		return new ExpansionPage(List.of(new ExpansionPage.Concept(conceptId, fsn.replaceAll(" \\(.*\\)$", ""), fsn, "en")),
				1, 0, 1);
	}

	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private static String setWith(String sctid) {
		return """
				{"name": "Asthma", "description": "", "ecl": "<< 195967001", "visibility": "COMMUNITY",
				 "sctid": "%s"}
				""".formatted(sctid);
	}

	private void check(String sctid, org.springframework.test.web.servlet.ResultMatcher expected, String detail)
			throws Exception {
		mvc.perform(get("/api/set-identifiers/check").param("sctid", sctid).param("branch", RELEASE)
						.with(signedInAs("olive")))
				.andExpect(expected)
				.andExpect(jsonPath("$.detail").value(containsString(detail)));
	}

	@Test
	void showsTheReferenceSetConceptAnIdentifierNames() throws Exception {
		mvc.perform(get("/api/set-identifiers/check").param("sctid", REFSET).param("branch", RELEASE)
						.with(signedInAs("olive")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conceptId").value(REFSET))
				.andExpect(jsonPath("$.fullySpecifiedName").value(REFSET_FSN));
	}

	@Test
	void refusesIdentifiersThatAreMistypedOrNotForAConceptWithoutAskingSnowstorm() throws Exception {
		check("723264002", status().isBadRequest(), "not a valid SNOMED CT identifier");
		check("12345", status().isBadRequest(), "not a valid SNOMED CT identifier");
		check("12345013", status().isBadRequest(), "not a concept");
		verify(snowstorm, never()).expand(anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void refusesAConceptThatIsNotAReferenceSetOrNotInTheRelease() throws Exception {
		check(NOT_A_REFSET, status().isBadRequest(), "is not a reference set");
		check("446609009", status().isBadRequest(), "is not an active concept in the 2026-01-01 release");
	}

	@Test
	void saysSoWhenSnowstormCannotBeReached() throws Exception {
		given(snowstorm.expand(anyString(), anyString(), anyInt(), anyInt()))
				.willThrow(new SnowstormException("down", false, null));
		check(REFSET, status().isServiceUnavailable(), "could not be checked");
	}

	@Test
	void savesTheIdentifierAndFindsTheSetByIt() throws Exception {
		mvc.perform(post("/api/value-sets").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sctid").value(REFSET))
				.andExpect(jsonPath("$.sctidTerm").value(REFSET_FSN));
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE sctid = ?", Long.class, REFSET);

		mvc.perform(get("/api/value-sets/sct/" + REFSET).with(signedInAs("rita")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id));
		// A draft is not shown to visitors, by its identifier or otherwise.
		mvc.perform(get("/api/value-sets/sct/" + REFSET)).andExpect(status().isNotFound());
		mvc.perform(get("/api/value-sets/sct/446609009").with(signedInAs("rita"))).andExpect(status().isNotFound());
	}

	@Test
	void refusesAnIdentifierAnotherSetAlreadyHas() throws Exception {
		mvc.perform(post("/api/value-sets").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET)))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/value-sets").with(signedInAs("rita")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("already identifies the value set \"Asthma\"")));
		mvc.perform(get("/api/set-identifiers/check").param("sctid", REFSET).param("branch", RELEASE)
						.with(signedInAs("rita")))
				.andExpect(status().isConflict());
	}

	@Test
	void doesNotNameAPrivateSetThatHoldsTheIdentifier() throws Exception {
		mvc.perform(post("/api/value-sets").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET).replace("COMMUNITY", "PRIVATE")))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/set-identifiers/check").param("sctid", REFSET).param("branch", RELEASE)
						.with(signedInAs("rita")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("723264001 already identifies another value set."));
	}

	@Test
	void keepsOrRemovesTheIdentifierWhenTheSetIsEdited() throws Exception {
		mvc.perform(post("/api/value-sets").with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET)))
				.andExpect(status().isCreated());
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE sctid = ?", Long.class, REFSET);

		// Keeping its own identifier is not a clash, and Snowstorm is not asked again.
		given(snowstorm.expand(eq(REFSET + " AND << 900000000000455006"), anyString(), anyInt(), anyInt()))
				.willThrow(new SnowstormException("down", false, null));
		mvc.perform(put("/api/value-sets/" + id).with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith(REFSET).replace("Asthma", "Asthma, renamed")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sctid").value(REFSET));

		mvc.perform(put("/api/value-sets/" + id).with(signedInAs("olive")).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(setWith("")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sctid").doesNotExist());
	}
}
