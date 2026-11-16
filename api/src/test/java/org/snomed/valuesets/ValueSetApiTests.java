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
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
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

	private static final Concept ASTHMA = new Concept("195967001", "Asthma", "Asthma (disorder)");
	private static final Concept CHILDHOOD_ASTHMA = new Concept("233678006", "Childhood asthma",
			"Childhood asthma (disorder)");
	private static final Concept ALLERGIC_ASTHMA = new Concept("389145006", "Allergic asthma",
			"Allergic asthma (disorder)");

	private static String handPicked(String visibility, String... conceptIds) {
		return """
				{"type": "MANUAL", "name": "Picked asthma codes", "description": "", "branch": "MAIN",
				 "visibility": "%s", "conceptIds": ["%s"]}
				""".formatted(visibility, String.join("\", \"", conceptIds));
	}

	private long createHandPicked(String subject, String visibility) throws Exception {
		given(snowstorm.expand("195967001 OR 233678006", "MAIN", 0, 100))
				.willReturn(new ExpansionPage(List.of(CHILDHOOD_ASTHMA, ASTHMA), 2, 0, 100));
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(handPicked(visibility, "195967001", "233678006")).with(signedInAs(subject)).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("MANUAL"))
				.andExpect(jsonPath("$.ecl").value(""));
		return jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = ?", Long.class, subject);
	}

	@Test
	void handPickedSetStoresTermsFromSnowstormAndListsThemWithoutCallingItAgain() throws Exception {
		long id = createHandPicked("alice", "PRIVATE");
		org.mockito.Mockito.clearInvocations(snowstorm);

		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Asthma"))
				.andExpect(jsonPath("$.items[1].conceptId").value("233678006"));
		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$", hasSize(2)));

		org.mockito.Mockito.verifyNoInteractions(snowstorm);
	}

	@Test
	void handPickedSetRejectsAConceptSnowstormDoesNotKnow() throws Exception {
		given(snowstorm.expand("195967001 OR 999999999", "MAIN", 0, 100))
				.willReturn(new ExpansionPage(List.of(ASTHMA), 1, 0, 100));

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "195967001", "999999999")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("These concepts are not active concepts on MAIN: 999999999"));
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "not-a-concept-id")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void editingAHandPickedListOnlyLooksUpTheNewConcepts() throws Exception {
		long id = createHandPicked("alice", "PRIVATE");
		given(snowstorm.expand("389145006", "MAIN", 0, 100))
				.willReturn(new ExpansionPage(List.of(ALLERGIC_ASTHMA), 1, 0, 100));

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "233678006", "389145006")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk());

		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Allergic asthma"))
				.andExpect(jsonPath("$.items[1].preferredTerm").value("Childhood asthma"));
	}

	@Test
	void publishingAHandPickedSetFreezesItsListForEveryoneElse() throws Exception {
		long id = createHandPicked("alice", "COMMUNITY");
		publish(id, "alice", "First list");

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("COMMUNITY", "233678006")).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.unpublishedChanges").value(true));

		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.total").value(1));
		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(get("/api/value-sets/{id}/versions", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$[0].conceptCount").value(2));

		publish(id, "alice", "Removed the parent concept");
		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(1));
		mvc.perform(get("/api/value-sets/{id}/versions/1/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(2));
	}

	@Test
	void anEmptyHandPickedSetCanBeSavedButNotPublished() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"type": "MANUAL", "name": "Empty", "description": "", "branch": "MAIN",
						 "visibility": "PRIVATE", "conceptIds": []}
						""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated());
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = 'alice'", Long.class);

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Add at least one concept before publishing."));
	}

	@Test
	void theKindOfDefinitionCannotBeChangedAndTheWorkingListIsForTheOwnerOnly() throws Exception {
		long id = createHandPicked("alice", "COMMUNITY");

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("bob")))
				.andExpect(status().isForbidden());
	}

	@Test
	void anEclSetNeedsAnExpression() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("<< 233678006", " ")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void conceptSearchPassesTheTermToSnowstorm() throws Exception {
		given(snowstorm.search("childhood asthma", "MAIN", 25))
				.willReturn(new ExpansionPage(List.of(CHILDHOOD_ASTHMA), 5, 0, 25));

		mvc.perform(get("/api/concepts").param("term", "childhood asthma").with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(5))
				.andExpect(jsonPath("$.items[0].conceptId").value("233678006"));
		mvc.perform(get("/api/concepts").param("term", "as").with(signedInAs("alice")))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/concepts").param("term", "asthma")).andExpect(status().isUnauthorized());
	}

	@Test
	void childrenOfAConceptAreTheDirectChildrenOnly() throws Exception {
		given(snowstorm.expand("<! 195967001", "MAIN", 0, 100))
				.willReturn(new ExpansionPage(List.of(CHILDHOOD_ASTHMA, ALLERGIC_ASTHMA), 2, 0, 100));

		mvc.perform(get("/api/concepts/195967001/children").with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items", hasSize(2)));
		mvc.perform(get("/api/concepts/195967001%20OR%20*/children").with(signedInAs("alice")))
				.andExpect(status().isBadRequest());
	}

	private void publish(long id, String subject, String note) throws Exception {
		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"" + note + "\"}").with(signedInAs(subject)).with(csrf()))
				.andExpect(status().isCreated());
	}

	private static ExpansionPage pageOf(Concept... concepts) {
		return new ExpansionPage(List.of(concepts), concepts.length, 0, 1000);
	}

	@Test
	void publishingFreezesTheDefinitionForEveryoneElseUntilTheOwnerPublishesAgain() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		given(snowstorm.expand("<< 233678006", "MAIN", 0, 1000)).willReturn(pageOf(CHILDHOOD_ASTHMA));

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"First release\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.number").value(1))
				.andExpect(jsonPath("$.frozen").value(true))
				.andExpect(jsonPath("$.conceptCount").value(2));

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
	void aPublishedVersionKeepsItsConceptListWhateverSnowstormSaysLater() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");
		org.mockito.Mockito.reset(snowstorm);

		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[0].preferredTerm").value("Asthma"));
		mvc.perform(get("/api/value-sets/{id}/versions/1/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(get("/api/value-sets/{id}/versions/7/expansion", id).with(signedInAs("bob")))
				.andExpect(status().isNotFound());

		org.mockito.Mockito.verifyNoInteractions(snowstorm);
	}

	@Test
	void publishingCollectsEveryPageOfTheExpansion() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000))
				.willReturn(new ExpansionPage(List.of(ASTHMA, CHILDHOOD_ASTHMA), 1001, 0, 1000));
		given(snowstorm.expand("<< 195967001", "MAIN", 1000, 1000))
				.willReturn(new ExpansionPage(List.of(ALLERGIC_ASTHMA), 1001, 1000, 1000));

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.conceptCount").value(3));
	}

	@Test
	void aDefinitionThatMatchesTooManyConceptsCannotBePublished() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000))
				.willReturn(new ExpansionPage(List.of(ASTHMA), 250_000, 0, 1000));

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail")
						.value("This definition matches 250000 concepts. At most 10000 can be published."));
	}

	@Test
	void publishingAgainWithoutChangesIsRefused() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");

		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Nothing has changed since version 1."));
	}

	@Test
	void aDefinitionSnowstormRejectsCannotBePublished() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000))
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
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
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
