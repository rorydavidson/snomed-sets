package org.snomed.valuesets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
@ActiveProfiles({ "dev", "test" })
@Import(TestKeycloakConfig.class)
// Placeholders: the registration itself comes from TestKeycloakConfig, so no issuer is contacted.
@TestPropertySource(properties = { "KEYCLOAK_CLIENT_ID=test-client", "KEYCLOAK_CLIENT_SECRET=test-secret",
		"KEYCLOAK_ISSUER_URI=http://keycloak.test" })
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

	private static final String NEW_SET = """
			{"name": "Childhood asthma", "description": "Asthma with onset in childhood.",
			 "ecl": "<< 233678006", "branch": "MAIN", "visibility": "PRIVATE"}
			""";

	@AfterEach
	void removeSetsCreatedByTests() {
		jdbc.update("DELETE FROM value_set WHERE owner_subject <> 'sample-data'");
	}

	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private long createSetOwnedBy(String subject, String visibility) {
		jdbc.update("""
				INSERT INTO value_set (name, description, ecl, branch, status, visibility,
				                       owner_subject, owner_name, created_at, updated_at)
				VALUES ('Owned set', '', '<< 195967001', 'MAIN', 'DRAFT', ?, ?, 'Owner', NOW(6), NOW(6))
				""", visibility, subject);
		return jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = ?", Long.class, subject);
	}

	@Test
	void createdSetIsAPrivateDraftOwnedByTheCaller() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Childhood asthma"))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.ownerName").value("Person alice"))
				.andExpect(jsonPath("$.ownedByMe").value(true));

		mvc.perform(get("/api/value-sets").with(signedInAs("bob")))
				.andExpect(jsonPath("$[*].name", not(hasItem("Childhood asthma"))));
	}

	@Test
	void createWithoutCsrfTokenIsRefused() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")))
				.andExpect(status().isForbidden());
	}

	@Test
	void createRejectsMissingFieldsAndBadBranch() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("Childhood asthma", " "))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("MAIN", "../admin"))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void ownerCanUpdateTheirSet() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("PRIVATE", "COMMUNITY"))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Childhood asthma"))
				.andExpect(jsonPath("$.visibility").value("COMMUNITY"));

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ecl").value("<< 233678006"));
	}

	@Test
	void communitySetCannotBeChangedOrDeletedBySomeoneElse() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail").value("Only the owner can change this value set."));
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.name").value("Owned set"));
	}

	@Test
	void privateSetOfSomeoneElseCannotBeChangedAndIsReportedAsMissing() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isNotFound());
	}

	@Test
	void ownerCanDeleteTheirSet() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice"))).andExpect(status().isNotFound());
	}

	private void publish(long id, String subject, String note) throws Exception {
		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"" + note + "\"}").with(signedInAs(subject)).with(csrf()))
				.andExpect(status().isCreated());
	}

	private static ExpansionPage pageWithTotal(long total) {
		return new ExpansionPage(List.of(), total, 0, 1);
	}

	@Test
	void publishingFreezesTheDefinitionForEveryoneElseUntilTheOwnerPublishesAgain() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1)).willReturn(pageWithTotal(121));
		given(snowstorm.expand("<< 233678006", "MAIN", 0, 1)).willReturn(pageWithTotal(9));

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"First release\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.number").value(1))
				.andExpect(jsonPath("$.conceptCount").value(121));

		// The owner changes the definition but has not published it yet.
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("PRIVATE", "COMMUNITY")).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.ecl").value("<< 233678006"))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.unpublishedChanges").value(true));

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.ecl").value("<< 195967001"))
				.andExpect(jsonPath("$.latestVersion").value(1))
				.andExpect(jsonPath("$.unpublishedChanges").value(false));

		publish(id, "alice", "Narrowed to childhood asthma");

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.ecl").value("<< 233678006"))
				.andExpect(jsonPath("$.latestVersion").value(2));
		mvc.perform(get("/api/value-sets/{id}/versions", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].number").value(2))
				.andExpect(jsonPath("$[0].note").value("Narrowed to childhood asthma"))
				.andExpect(jsonPath("$[1].ecl").value("<< 195967001"));
	}

	@Test
	void anOlderVersionCanStillBeExpanded() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1)).willReturn(pageWithTotal(121));
		publish(id, "alice", "");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 50)).willReturn(new ExpansionPage(
				List.of(new ExpansionPage.Concept("195967001", "Asthma", "Asthma (disorder)")), 121, 0, 50));

		mvc.perform(get("/api/value-sets/{id}/versions/1/expansion", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(121));
		mvc.perform(get("/api/value-sets/{id}/versions/7/expansion", id).with(signedInAs("bob")))
				.andExpect(status().isNotFound());
	}

	@Test
	void publishingAgainWithoutChangesIsRefused() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1)).willReturn(pageWithTotal(121));
		publish(id, "alice", "");

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Nothing has changed since version 1."));
	}

	@Test
	void aDefinitionSnowstormRejectsCannotBePublished() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1))
				.willThrow(new SnowstormException("Snowstorm rejected the request.", true, null));

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.latestVersion").doesNotExist());
	}

	@Test
	void onlyTheOwnerCanPublish() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden());
	}

	@Test
	void aPublishedSharedSetMustBeWithdrawnBeforeItCanBeDeleted() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1)).willReturn(pageWithTotal(121));
		publish(id, "alice", "");

		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name": "Owned set", "description": "", "ecl": "<< 195967001",
						 "branch": "MAIN", "visibility": "PRIVATE"}
						""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk());
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void sampleSetsComeWithAPublishedVersion() throws Exception {
		mvc.perform(get("/api/value-sets").with(oidcLogin()))
				.andExpect(jsonPath("$[*].latestVersion", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(1))));
	}

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
	void signInLinkSendsASignedOutBrowserToKeycloak() throws Exception {
		mvc.perform(get("/sign-in").accept(MediaType.TEXT_HTML))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/oauth2/authorization/keycloak")));
	}

	@Test
	void thereIsNoPasswordSignInUnlessLocalLoginIsSwitchedOn() throws Exception {
		mvc.perform(post("/login").param("username", "anyone").param("password", "anything").with(csrf()))
				.andExpect(status().is4xxClientError());
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
