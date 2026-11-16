package org.snomed.valuesets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({ "dev", "test" })
@Import(TestKeycloakConfig.class)
class ValueSetApiTests {

	private static final String PRIVATE_SAMPLE = "Hypertension excluding pregnancy-related";

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	@Test
	void apiRequiresSignIn() throws Exception {
		mvc.perform(get("/api/value-sets")).andExpect(status().isUnauthorized());
	}

	@Test
	void listShowsCommunitySetsButNotOtherPeoplesPrivateSets() throws Exception {
		mvc.perform(get("/api/value-sets").with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].name", not(hasItem(PRIVATE_SAMPLE))))
				.andExpect(jsonPath("$[0].ownedByMe").value(false));
	}

	@Test
	void ownerSeesTheirOwnPrivateSet() throws Exception {
		mvc.perform(get("/api/value-sets").with(oidcLogin().idToken(token -> token.subject("sample-data"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(4)))
				.andExpect(jsonPath("$[*].name", hasItem(PRIVATE_SAMPLE)));
	}

	@Test
	void privateSetOfSomeoneElseIsReportedAsMissing() throws Exception {
		Long privateId = jdbc.queryForObject("SELECT id FROM value_set WHERE visibility = 'PRIVATE'", Long.class);

		mvc.perform(get("/api/value-sets/{id}", privateId).with(oidcLogin())).andExpect(status().isNotFound());
		mvc.perform(get("/api/value-sets/{id}/expansion", privateId).with(oidcLogin()))
				.andExpect(status().isNotFound());
	}

	@Test
	void expansionUsesTheSavedDefinition() throws Exception {
		Long asthmaId = jdbc.queryForObject("SELECT id FROM value_set WHERE name = 'Asthma diagnoses'", Long.class);
		given(snowstorm.expand("<< 195967001 |Asthma|", "MAIN", 0, 50)).willReturn(new ExpansionPage(
				List.of(new ExpansionPage.Concept("195967001", "Asthma", "Asthma (disorder)")), 120, 0, 50));

		mvc.perform(get("/api/value-sets/{id}/expansion", asthmaId).with(oidcLogin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(120))
				.andExpect(jsonPath("$.items[0].conceptId").value("195967001"));
	}

	@Test
	void pageSizeIsCapped() throws Exception {
		mvc.perform(get("/api/ecl/expansion").param("ecl", "<< 195967001").param("limit", "500").with(oidcLogin()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void meReturnsOnlyTheDisplayName() throws Exception {
		mvc.perform(get("/api/me").with(oidcLogin().idToken(token -> token.claim("name", "Sample Person"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Sample Person"))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void signOutReturnsWhereToGoNextInsteadOfRedirecting() throws Exception {
		mvc.perform(post("/logout").with(oidcLogin()).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().string("/"));
	}

	@Test
	void signOutWithoutCsrfTokenIsRefused() throws Exception {
		mvc.perform(post("/logout").with(oidcLogin())).andExpect(status().isForbidden());
	}
}
