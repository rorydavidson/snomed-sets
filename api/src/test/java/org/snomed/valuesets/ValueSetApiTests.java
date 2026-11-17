package org.snomed.valuesets;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.CodeSystemVersion;
import org.snomed.valuesets.snowstorm.ConceptDetails;
import org.snomed.valuesets.snowstorm.Edition;
import org.snomed.valuesets.snowstorm.ExpansionPage;
import org.snomed.valuesets.snowstorm.ExpansionPage.Concept;
import org.snomed.valuesets.snowstorm.Inactivation;
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

	/** Where a value set created through the API is put: the latest International Edition release. */
	private static final String RELEASE = "MAIN/2026-01-01";

	private static final String NEW_SET = """
			{"name": "Childhood asthma", "description": "Asthma with onset in childhood.",
			 "ecl": "<< 233678006", "branch": "MAIN", "visibility": "PRIVATE"}
			""";

	@BeforeEach
	void snowstormFindsNothingUnlessATestSaysOtherwise() {
		given(snowstorm.expand(anyString(), anyString(), anyInt(), anyInt()))
				.willReturn(new ExpansionPage(List.of(), 0, 0, 100));
	}

	@BeforeEach
	void snowstormHasTwoCodeSystems() {
		given(snowstorm.codeSystems()).willReturn(List.of(
				new CodeSystem("SNOMEDCT", "International Edition", "MAIN", "2026-01-01", "MAIN/2026-01-01"),
				new CodeSystem("SNOMEDCT-BE", "Belgian Edition", "MAIN/SNOMEDCT-BE", "2026-03-15",
						"MAIN/SNOMEDCT-BE/2026-03-15")));
	}

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
	void createRejectsMissingFieldsAndUnknownCodeSystem() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("Childhood asthma", " "))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystem\": \"SNOMEDCT-XX\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Snowstorm has no code system called SNOMEDCT-XX."));
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

	private static final Concept ASTHMA = new Concept("195967001", "Asthma", "Asthma (disorder)", "en");
	private static final Concept CHILDHOOD_ASTHMA = new Concept("233678006", "Childhood asthma",
			"Childhood asthma (disorder)", "en");
	private static final Concept ALLERGIC_ASTHMA = new Concept("389145006", "Allergic asthma",
			"Allergic asthma (disorder)", "en");

	private static String handPicked(String visibility, String... conceptIds) {
		return """
				{"type": "MANUAL", "name": "Picked asthma codes", "description": "", "branch": "MAIN",
				 "visibility": "%s", "conceptIds": ["%s"]}
				""".formatted(visibility, String.join("\", \"", conceptIds));
	}

	private long createHandPicked(String subject, String visibility) throws Exception {
		given(snowstorm.expand("195967001 OR 233678006", RELEASE, 0, 100))
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
				.andExpect(jsonPath("$.items[0].language").value("en"))
				.andExpect(jsonPath("$.items[1].conceptId").value("233678006"));
		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$", hasSize(2)));

		org.mockito.Mockito.verifyNoInteractions(snowstorm);
	}

	@Test
	void handPickedSetRejectsAConceptSnowstormDoesNotKnow() throws Exception {
		given(snowstorm.expand("195967001 OR 999999999", RELEASE, 0, 100))
				.willReturn(new ExpansionPage(List.of(ASTHMA), 1, 0, 100));

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "195967001", "999999999")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("These concepts are not active concepts in SNOMEDCT: 999999999"));
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "not-a-concept-id")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void editingAHandPickedListOnlyLooksUpTheNewConcepts() throws Exception {
		long id = createHandPicked("alice", "PRIVATE");
		given(snowstorm.expand("389145006", RELEASE, 0, 100))
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
	void theCodeSystemIsChosenOnCreationAndCannotBeChangedAfterwards() throws Exception {
		String belgian = NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystem\": \"SNOMEDCT-BE\"");
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON).content(belgian)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.codeSystem").value("SNOMEDCT-BE"))
				.andExpect(jsonPath("$.branch").value("MAIN/SNOMEDCT-BE/2026-03-15"));
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = 'alice'", Long.class);

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("The code system of a value set cannot be changed."));
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(belgian.replace("Childhood asthma", "Renamed"))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.branch").value("MAIN/SNOMEDCT-BE/2026-03-15"));
	}

	@Test
	void codeSystemsComeFromSnowstorm() throws Exception {
		mvc.perform(get("/api/code-systems").with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[1].shortName").value("SNOMEDCT-BE"));
	}

	@Test
	void savingWritesTheTermAfterIdentifiersTypedWithoutOne() throws Exception {
		given(snowstorm.expand("233678006", RELEASE, 0, 100))
				.willReturn(new ExpansionPage(List.of(CHILDHOOD_ASTHMA), 1, 0, 100));

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ecl").value("<< 233678006 |Childhood asthma|"));
	}

	@Test
	void savingStillWorksWhenSnowstormCannotSupplyTheTerms() throws Exception {
		given(snowstorm.expand("233678006", RELEASE, 0, 100))
				.willThrow(new SnowstormException("Snowstorm could not be reached.", false, null));

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ecl").value("<< 233678006"));
	}

	private static String tagged(String visibility, String tags) {
		return """
				{"name": "Tagged set", "description": "", "ecl": "<< 195967001 |Asthma|",
				 "visibility": "%s", "tags": [%s]}
				""".formatted(visibility, tags);
	}

	@Test
	void tagsAreStoredInLowerCaseWithoutDuplicatesAndCanBeReplaced() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(tagged("PRIVATE", "\" Respiratory \", \"primary  care\", \"respiratory\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tags", contains("primary care", "respiratory")));
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = 'alice'", Long.class);

		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(tagged("PRIVATE", "\"asthma\"")).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.tags", contains("asthma")));
		mvc.perform(get("/api/value-sets").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].tags", contains("asthma")));
	}

	@Test
	void aTagWithUnusableCharactersIsRefused() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(tagged("PRIVATE", "\"<script>\"")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void tagSuggestionsLeaveOutTagsOfOtherPeoplesPrivateSets() throws Exception {
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(tagged("PRIVATE", "\"alice-only\"")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/tags").with(signedInAs("alice")))
				.andExpect(jsonPath("$", hasItem("alice-only")))
				.andExpect(jsonPath("$", hasItem("respiratory")));
		mvc.perform(get("/api/tags").with(signedInAs("bob")))
				.andExpect(jsonPath("$", not(hasItem("alice-only"))))
				.andExpect(jsonPath("$", not(hasItem("cardiovascular"))))
				.andExpect(jsonPath("$", hasItem("respiratory")));
	}

	@Test
	void aSharedPublishedSetDownloadsAsAGpsFileOfItsLatestVersion() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		jdbc.update("UPDATE value_set SET name = 'Asthma: réf / v2!' WHERE id = ?", id);
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(CHILDHOOD_ASTHMA, ASTHMA));
		publish(id, "alice", "");
		org.mockito.Mockito.reset(snowstorm);

		mvc.perform(get("/api/value-sets/{id}/download", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/tab-separated-values;charset=UTF-8"))
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"asthma-re-f-v2-v1.tsv\""))
				.andExpect(content().string("""
						SNOMED CT ID\tFSN (en-us)\tPT (en-us)\tActive
						195967001\tAsthma (disorder)\tAsthma\t1
						233678006\tChildhood asthma (disorder)\tChildhood asthma\t1
						"""));
		org.mockito.Mockito.verifyNoInteractions(snowstorm);
	}

	@Test
	void aHandPickedSharedSetDownloadsItsPublishedList() throws Exception {
		long id = createHandPicked("alice", "COMMUNITY");
		publish(id, "alice", "");

		mvc.perform(get("/api/value-sets/{id}/download", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("233678006\tChildhood asthma (disorder)")));
	}

	@Test
	void aSetWithNoPublishedVersionHasNothingToDownloadExceptItsDraftAndOnlyWhenSignedIn() throws Exception {
		long unpublished = createSetOwnedBy("alice", "COMMUNITY");
		mvc.perform(get("/api/value-sets/{id}/download", unpublished).with(signedInAs("bob")))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/value-sets/{id}/download", unpublished)).andExpect(status().isUnauthorized());

		jdbc.update("UPDATE value_set SET visibility = 'PRIVATE' WHERE id = ?", unpublished);
		mvc.perform(get("/api/value-sets/{id}/download", unpublished).with(signedInAs("alice")))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/value-sets/{id}/download", unpublished).with(signedInAs("bob")))
				.andExpect(status().isNotFound());
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
	void childCountsAreGivenForEachConceptAskedAbout() throws Exception {
		given(snowstorm.expand("<! 195967001", "MAIN", 0, 1)).willReturn(new ExpansionPage(List.of(), 27, 0, 1));

		mvc.perform(get("/api/concepts/child-counts").param("conceptIds", "195967001", "233678006")
				.with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$['195967001']").value(27))
				.andExpect(jsonPath("$['233678006']").value(0));
		mvc.perform(get("/api/concepts/child-counts").param("conceptIds", "1 OR *").with(signedInAs("alice")))
				.andExpect(status().isBadRequest());
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
						.value("This definition matches 250000 concepts. At most 10000 can be published or downloaded."));
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

	private void deprecate(String path, long id, String subject) throws Exception {
		mvc.perform(put(path, id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \"Replaced by a newer set\"}").with(signedInAs(subject)).with(csrf()))
				.andExpect(status().isOk());
	}

	@Test
	void aPublishedSetCannotBeDeletedOnlyDeprecated() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");

		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail")
						.value("A value set with a published version in use cannot be deleted. Deprecate every version first."));
	}

	@Test
	void aSetWhoseEveryVersionIsDeprecatedCanBeDeleted() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");
		jdbc.update("UPDATE value_set SET ecl = '<< 233678006' WHERE id = ?", id);
		given(snowstorm.expand("<< 233678006", "MAIN", 0, 1000)).willReturn(pageOf(CHILDHOOD_ASTHMA));
		publish(id, "alice", "");

		deprecate("/api/value-sets/{id}/versions/2/deprecation", id, "alice");
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.allVersionsDeprecated").value(false));

		deprecate("/api/value-sets/{id}/versions/1/deprecation", id, "alice");
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.allVersionsDeprecated").value(true));
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/value-sets/{id}", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(status().isNotFound());
	}

	@Test
	void aDeprecatedSetStaysReadableSaysWhyAndCanBeRestored() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");

		deprecate("/api/value-sets/{id}/deprecation", id, "alice");

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.deprecated").value(true))
				.andExpect(jsonPath("$.deprecationReason").value("Replaced by a newer set"));
		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());

		mvc.perform(delete("/api/value-sets/{id}/deprecation", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.deprecated").value(false))
				.andExpect(jsonPath("$.deprecationReason").doesNotExist());
	}

	@Test
	void deprecationNeedsAReasonAPublishedSetAndTheOwner() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");

		mvc.perform(put("/api/value-sets/{id}/deprecation", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \"No longer needed\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());
		mvc.perform(put("/api/value-sets/{id}/deprecation", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \" \"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/value-sets/{id}/deprecation", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \"Not mine\"}").with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/value-sets/{id}/versions/1/deprecation", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\": \"Not mine\"}").with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isForbidden());
	}

	@Test
	void deprecatingTheNewestVersionPutsOtherPeopleBackOnTheOneBefore() throws Exception {
		long id = createSetOwnedBy("alice", "COMMUNITY");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		given(snowstorm.expand("<< 233678006", "MAIN", 0, 1000)).willReturn(pageOf(CHILDHOOD_ASTHMA));
		publish(id, "alice", "");
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("PRIVATE", "COMMUNITY")).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk());
		publish(id, "alice", "Published by mistake");

		deprecate("/api/value-sets/{id}/versions/2/deprecation", id, "alice");

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.latestVersion").value(1))
				.andExpect(jsonPath("$.ecl").value("<< 195967001"))
				.andExpect(jsonPath("$.deprecated").value(false));
		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(get("/api/value-sets/{id}/versions", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$[0].number").value(2))
				.andExpect(jsonPath("$[0].deprecated").value(true))
				.andExpect(jsonPath("$[0].deprecationReason").value("Replaced by a newer set"))
				.andExpect(jsonPath("$[1].deprecated").value(false));
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.unpublishedChanges").value(true));

		// The number of a deprecated version is not given out again.
		mvc.perform(post("/api/value-sets/{id}/versions", id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\": \"Second attempt\"}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.number").value(3));

		mvc.perform(delete("/api/value-sets/{id}/versions/2/deprecation", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.deprecated").value(false));
	}

	@Test
	void discardingChangesPutsAnEclDraftBackToThePublishedVersion() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		publish(id, "alice", "");
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON).content(NEW_SET)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.unpublishedChanges").value(true));

		mvc.perform(post("/api/value-sets/{id}/discard-changes", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ecl").value("<< 195967001"))
				.andExpect(jsonPath("$.name").value("Childhood asthma"))
				.andExpect(jsonPath("$.unpublishedChanges").value(false));
	}

	@Test
	void discardingChangesPutsAHandPickedListBackToThePublishedOne() throws Exception {
		long id = createHandPicked("alice", "PRIVATE");
		publish(id, "alice", "");
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(handPicked("PRIVATE", "233678006")).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.unpublishedChanges").value(true));

		mvc.perform(post("/api/value-sets/{id}/discard-changes", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.unpublishedChanges").value(false));
		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void thereIsNothingToDiscardOnASetThatWasNeverPublished() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(post("/api/value-sets/{id}/discard-changes", id).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/value-sets/{id}/discard-changes", id).with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isNotFound());
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
				List.of(new ExpansionPage.Concept("195967001", "Asthma", "Asthma (disorder)", "en")), 120, 0, 50));

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

	// Upgrading to a newer release

	private static final String OLD_RELEASE = "MAIN/2025-07-01";
	private static final Concept OLD_ASTHMA_TERM = new Concept("195967001", "Asthma (old term)", "Asthma (disorder)", "en");
	private static final Concept RETIRED = new Concept("1234567", "Retired asthma concept", "Retired (disorder)", "en");

	private long insertSet(String subject, String visibility, String type, String ecl, String branch,
			Concept... members) {
		jdbc.update("""
				INSERT INTO value_set (name, description, definition_type, code_system, ecl, branch, status,
				                       visibility, owner_subject, owner_name, created_at, updated_at)
				VALUES ('Upgrade me', '', ?, 'SNOMEDCT', ?, ?, 'DRAFT', ?, ?, 'Owner', NOW(6), NOW(6))
				""", type, ecl, branch, visibility, subject);
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = ?", Long.class, subject);
		for (Concept member : members) {
			jdbc.update("""
					INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
					                              preferred_term_language, fully_specified_name)
					VALUES (?, 0, ?, ?, 'en', ?)
					""", id, member.conceptId(), member.preferredTerm(), member.fullySpecifiedName());
		}
		return id;
	}

	private static String upgradeRequest(String targetVersion, boolean publish, String... accepted) {
		StringBuilder replacements = new StringBuilder();
		for (int i = 0; i < accepted.length; i += 2) {
			replacements.append(i == 0 ? "" : ",").append("""
					{"inactiveConceptId": "%s", "replacementConceptId": "%s"}""".formatted(accepted[i], accepted[i + 1]));
		}
		return """
				{"targetVersion": "%s", "publish": %s, "note": "Moved to the January release",
				 "acceptedReplacements": [%s]}
				""".formatted(targetVersion, publish, replacements);
	}

	private void eclChangesBetweenReleases() {
		given(snowstorm.expand("<< 195967001", OLD_RELEASE, 0, 1000)).willReturn(pageOf(OLD_ASTHMA_TERM, CHILDHOOD_ASTHMA));
		given(snowstorm.expand("<< 195967001", RELEASE, 0, 1000)).willReturn(pageOf(ASTHMA, ALLERGIC_ASTHMA));
	}

	@Test
	void theOwnerIsToldWhenANewerReleaseIsAvailable() throws Exception {
		long id = insertSet("alice", "COMMUNITY", "ECL", "<< 195967001", OLD_RELEASE);

		mvc.perform(get("/api/value-sets/{id}/upgrade", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.currentVersion").value("2025-07-01"))
				.andExpect(jsonPath("$.latestVersion").value("2026-01-01"));
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.codeSystemVersion").value("2025-07-01"));
		mvc.perform(get("/api/value-sets/{id}/upgrade", id).with(signedInAs("bob")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/value-sets/{id}/upgrade/preview", id).with(signedInAs("bob")))
				.andExpect(status().isForbidden());
	}

	@Test
	void aSetOnTheLatestReleaseHasNothingToUpgradeTo() throws Exception {
		long id = insertSet("alice", "PRIVATE", "ECL", "<< 195967001", RELEASE);

		mvc.perform(get("/api/value-sets/{id}/upgrade", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.available").value(false));
		mvc.perform(get("/api/value-sets/{id}/upgrade/preview", id).with(signedInAs("alice")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("This value set is already on the latest release of SNOMEDCT, 2026-01-01."));
	}

	@Test
	void aSetOnTheLiveBranchCanBeMovedOntoTheLatestRelease() throws Exception {
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(get("/api/value-sets/{id}/upgrade", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.currentVersion").doesNotExist());
	}

	@Test
	void previewingAnEclUpgradeShowsWhatWouldChangeWithoutSavingAnything() throws Exception {
		long id = insertSet("alice", "PRIVATE", "ECL", "<< 195967001", OLD_RELEASE);
		eclChangesBetweenReleases();

		mvc.perform(get("/api/value-sets/{id}/upgrade/preview", id).with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromVersion").value("2025-07-01"))
				.andExpect(jsonPath("$.toVersion").value("2026-01-01"))
				.andExpect(jsonPath("$.conceptCountBefore").value(2))
				.andExpect(jsonPath("$.added[*].conceptId", contains("389145006")))
				.andExpect(jsonPath("$.removed[*].conceptId", contains("233678006")))
				.andExpect(jsonPath("$.termChanges[0].oldTerm").value("Asthma (old term)"))
				.andExpect(jsonPath("$.termChanges[0].newTerm").value("Asthma"));

		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.branch").value(OLD_RELEASE));
		mvc.perform(get("/api/value-sets/{id}/upgrades", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void upgradingAnEclSetAndPublishingKeepsAReportEveryoneWhoSeesTheSetCanDownload() throws Exception {
		long id = insertSet("alice", "COMMUNITY", "ECL", "<< 195967001", OLD_RELEASE);
		eclChangesBetweenReleases();

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content(upgradeRequest("2026-01-01", true)).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valueSet.branch").value(RELEASE))
				.andExpect(jsonPath("$.valueSet.latestVersion").value(1))
				.andExpect(jsonPath("$.upgrade.publishedVersion").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.ADDED").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.REMOVED").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.TERM_CHANGED").value(1));

		mvc.perform(get("/api/value-sets/{id}/versions/1/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.items[*].conceptId", contains("389145006", "195967001")));
		long upgradeId = jdbc.queryForObject("SELECT id FROM value_set_upgrade WHERE value_set_id = ?", Long.class, id);
		mvc.perform(get("/api/value-sets/{id}/upgrades", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$[0].fromVersion").value("2025-07-01"))
				.andExpect(jsonPath("$[0].toVersion").value("2026-01-01"));
		mvc.perform(get("/api/value-sets/{id}/upgrades/{upgradeId}/report", id, upgradeId).with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition",
						"attachment; filename=\"upgrade-me-upgrade-2025-07-01-to-2026-01-01.csv\""))
				.andExpect(content().string(containsString("ADDED,\"389145006\",\"Allergic asthma\",,,\r\n")))
				.andExpect(content().string(containsString("REMOVED,\"233678006\",\"Childhood asthma\",,,\r\n")))
				.andExpect(content().string(containsString(
						"TERM_CHANGED,\"195967001\",\"Asthma\",,\"Asthma (old term)\",\r\n")));
	}

	@Test
	void upgradeReportsOfAPrivateSetAreHiddenFromOthers() throws Exception {
		long id = insertSet("alice", "PRIVATE", "ECL", "<< 195967001", OLD_RELEASE);
		eclChangesBetweenReleases();
		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content(upgradeRequest("2026-01-01", false)).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.upgrade.publishedVersion").doesNotExist());
		long upgradeId = jdbc.queryForObject("SELECT id FROM value_set_upgrade WHERE value_set_id = ?", Long.class, id);

		mvc.perform(get("/api/value-sets/{id}/upgrades", id).with(signedInAs("bob")))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/value-sets/{id}/upgrades/{upgradeId}/report", id, upgradeId).with(signedInAs("bob")))
				.andExpect(status().isNotFound());
	}

	@Test
	void anUpgradeIsRefusedIfANewerReleaseCameOutAfterThePreview() throws Exception {
		long id = insertSet("alice", "PRIVATE", "ECL", "<< 195967001", OLD_RELEASE);

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content(upgradeRequest("2025-12-01", false)).with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/value-sets/{id}", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.branch").value(OLD_RELEASE));
	}

	private void retiredConceptHasReplacements() {
		given(snowstorm.activeConcepts(List.of("195967001", "1234567"), RELEASE)).willReturn(pageOf(ASTHMA));
		Map<String, List<String>> targets = new LinkedHashMap<>();
		targets.put("SAME_AS", List.of("233678006"));
		targets.put("POSSIBLY_EQUIVALENT_TO", List.of("389145006", "195967001"));
		given(snowstorm.inactivation("1234567", RELEASE)).willReturn(new Inactivation(false, "Old term", "DUPLICATE", targets));
		given(snowstorm.activeConcepts(List.of("233678006", "389145006", "195967001"), RELEASE))
				.willReturn(pageOf(CHILDHOOD_ASTHMA, ALLERGIC_ASTHMA, ASTHMA));
	}

	@Test
	void previewingAHandPickedUpgradeSuggestsReplacementsForInactiveConcepts() throws Exception {
		long id = insertSet("alice", "PRIVATE", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		retiredConceptHasReplacements();

		mvc.perform(get("/api/value-sets/{id}/upgrade/preview", id).with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("MANUAL"))
				.andExpect(jsonPath("$.inactivated", hasSize(1)))
				.andExpect(jsonPath("$.inactivated[0].conceptId").value("1234567"))
				.andExpect(jsonPath("$.inactivated[0].reason").value("DUPLICATE"))
				.andExpect(jsonPath("$.inactivated[0].replacements[*].conceptId",
						contains("389145006", "195967001", "233678006")))
				.andExpect(jsonPath("$.inactivated[0].replacements[0].association").value("POSSIBLY_EQUIVALENT_TO"))
				.andExpect(jsonPath("$.inactivated[0].replacements[1].alreadyInSet").value(true))
				.andExpect(jsonPath("$.termChanges[0].newTerm").value("Asthma"));
	}

	@Test
	void upgradingAHandPickedSetAddsOnlyTheAcceptedReplacements() throws Exception {
		long id = insertSet("alice", "PRIVATE", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		retiredConceptHasReplacements();

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content(upgradeRequest("2026-01-01", false, "1234567", "233678006")).with(signedInAs("alice"))
				.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.upgrade.changeCounts.INACTIVATED").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.REPLACEMENT_ADDED").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.REPLACEMENT_DECLINED").value(1));

		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$[*].conceptId", contains("195967001", "233678006")))
				.andExpect(jsonPath("$[0].preferredTerm").value("Asthma"))
				.andExpect(jsonPath("$[1].fullySpecifiedName").value("Childhood asthma (disorder)"));
		long upgradeId = jdbc.queryForObject("SELECT id FROM value_set_upgrade WHERE value_set_id = ?", Long.class, id);
		mvc.perform(get("/api/value-sets/{id}/upgrades/{upgradeId}/report", id, upgradeId).with(signedInAs("alice")))
				.andExpect(content().string(containsString(
						"INACTIVATED,\"1234567\",\"Retired asthma concept\",,,\"DUPLICATE\"\r\n"
								+ "REPLACEMENT_DECLINED,\"389145006\",\"Allergic asthma\",\"1234567\",\"Retired asthma concept\",\"POSSIBLY_EQUIVALENT_TO\"\r\n"
								+ "REPLACEMENT_ADDED,\"233678006\",\"Childhood asthma\",\"1234567\",\"Retired asthma concept\",\"SAME_AS\"\r\n")));
	}

	@Test
	void anUpgradeCannotAddAConceptThatWasNotSuggested() throws Exception {
		long id = insertSet("alice", "PRIVATE", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		retiredConceptHasReplacements();

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content(upgradeRequest("2026-01-01", false, "1234567", "44054006")).with(signedInAs("alice"))
				.with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("44054006 is not a suggested replacement for 1234567."));
		mvc.perform(get("/api/value-sets/{id}/members", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$[*].conceptId", contains("195967001", "1234567")));
	}

	@Test
	void aNewSetCanBeBuiltOnAnEarlierReleaseAndIsThenOfferedTheUpgrade() throws Exception {
		given(snowstorm.codeSystemVersions("SNOMEDCT")).willReturn(List.of(
				new CodeSystemVersion("2026-01-01", RELEASE), new CodeSystemVersion("2025-07-01", OLD_RELEASE)));

		mvc.perform(get("/api/code-systems/SNOMEDCT/versions").with(signedInAs("alice")))
				.andExpect(jsonPath("$[*].version", contains("2026-01-01", "2025-07-01")));
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystemVersion\": \"2025-07-01\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.branch").value(OLD_RELEASE))
				.andExpect(jsonPath("$.codeSystemVersion").value("2025-07-01"));
		long id = jdbc.queryForObject("SELECT id FROM value_set WHERE owner_subject = 'alice'", Long.class);

		mvc.perform(get("/api/value-sets/{id}/upgrade", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.available").value(true));
		// Editing never moves the set to another release; only an upgrade does.
		mvc.perform(put("/api/value-sets/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystemVersion\": \"2026-01-01\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.branch").value(OLD_RELEASE));
	}

	@Test
	void aReleaseTheCodeSystemDoesNotHaveIsRefused() throws Exception {
		given(snowstorm.codeSystemVersions("SNOMEDCT")).willReturn(List.of(new CodeSystemVersion("2026-01-01", RELEASE)));

		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystemVersion\": \"2024-01-01\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("SNOMEDCT has no release 2024-01-01."));
		mvc.perform(post("/api/value-sets").contentType(MediaType.APPLICATION_JSON)
				.content(NEW_SET.replace("\"branch\": \"MAIN\"", "\"codeSystemVersion\": \"January\""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aSharedHandPickedSetDownloadsAsAFhirValueSetPinnedToItsRelease() throws Exception {
		given(snowstorm.editionModuleId("SNOMEDCT")).willReturn("900000000000207008");
		long id = createHandPicked("alice", "COMMUNITY");
		publish(id, "alice", "");

		mvc.perform(get("/api/value-sets/{id}/download", id).param("format", "fhir").with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/fhir+json"))
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"picked-asthma-codes-v1.json\""))
				.andExpect(jsonPath("$.resourceType").value("ValueSet"))
				.andExpect(jsonPath("$.id").value(String.valueOf(id)))
				.andExpect(jsonPath("$.url").value("http://localhost/value-sets/" + id))
				.andExpect(jsonPath("$.version").value("1"))
				.andExpect(jsonPath("$.name").value("PickedAsthmaCodes"))
				.andExpect(jsonPath("$.status").value("active"))
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.compose.include[0].system").value("http://snomed.info/sct"))
				.andExpect(jsonPath("$.compose.include[0].version")
						.value("http://snomed.info/sct/900000000000207008/version/20260101"))
				.andExpect(jsonPath("$.compose.include[0].concept[*].code", contains("195967001", "233678006")))
				.andExpect(jsonPath("$.expansion.total").value(2))
				.andExpect(jsonPath("$.expansion.contains[0].display").value("Asthma"))
				// Kept out because the file may travel beyond the people who can see the set.
				.andExpect(jsonPath("$.publisher").doesNotExist());
	}

	@Test
	void anEclSetOnTheLiveBranchDownloadsAsAFhirValueSetWithItsConstraintAndNoRelease() throws Exception {
		given(snowstorm.editionModuleId("SNOMEDCT")).willReturn("900000000000207008");
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		long id = createSetOwnedBy("alice", "COMMUNITY");
		publish(id, "alice", "First");

		mvc.perform(get("/api/value-sets/{id}/download", id).param("format", "fhir").with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.compose.include[0].version").doesNotExist())
				.andExpect(jsonPath("$.compose.include[0].filter[0].property").value("constraint"))
				.andExpect(jsonPath("$.compose.include[0].filter[0].op").value("="))
				.andExpect(jsonPath("$.compose.include[0].filter[0].value").value("<< 195967001"))
				.andExpect(jsonPath("$.compose.include[0].concept").doesNotExist())
				.andExpect(jsonPath("$.expansion.contains", hasSize(2)));
	}

	@Test
	void theOwnerCanDownloadTheWorkingDraftOfAPrivateSetButNobodyElseCan() throws Exception {
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		long id = createSetOwnedBy("alice", "PRIVATE");

		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "draft").with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"owned-set-draft.tsv\""))
				.andExpect(content().string(containsString("195967001\tAsthma (disorder)\tAsthma\t1\n")));
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "draft").param("format", "fhir")
				.with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value("draft"))
				.andExpect(jsonPath("$.status").value("draft"))
				.andExpect(jsonPath("$.expansion.total").value(2));
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "draft").with(signedInAs("bob")))
				.andExpect(status().isNotFound());

		jdbc.update("UPDATE value_set SET visibility = 'COMMUNITY' WHERE id = ?", id);
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "draft").with(signedInAs("bob")))
				.andExpect(status().isForbidden());
	}

	@Test
	void anyoneWhoCanSeeASetCanDownloadAnOlderVersion() throws Exception {
		given(snowstorm.expand("<< 195967001", "MAIN", 0, 1000)).willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA));
		given(snowstorm.expand("<< 233678006", "MAIN", 0, 1000)).willReturn(pageOf(CHILDHOOD_ASTHMA));
		long id = createSetOwnedBy("alice", "COMMUNITY");
		publish(id, "alice", "First");
		jdbc.update("UPDATE value_set SET ecl = '<< 233678006' WHERE id = ?", id);
		publish(id, "alice", "Narrower");

		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "1").with(signedInAs("bob")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"owned-set-v1.tsv\""))
				.andExpect(content().string(containsString("195967001\t")));
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "1").param("format", "fhir")
				.with(signedInAs("bob")))
				.andExpect(jsonPath("$.version").value("1"))
				.andExpect(jsonPath("$.compose.include[0].filter[0].value").value("<< 195967001"));
		mvc.perform(get("/api/value-sets/{id}/download", id).with(signedInAs("bob")))
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"owned-set-v2.tsv\""));
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "9").with(signedInAs("bob")))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/value-sets/{id}/download", id).param("version", "latest").with(signedInAs("bob")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anInactiveConceptTheOwnerKeepsStaysInTheListFlaggedInEveryDownload() throws Exception {
		given(snowstorm.editionModuleId("SNOMEDCT")).willReturn("900000000000207008");
		long id = insertSet("alice", "COMMUNITY", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		retiredConceptHasReplacements();

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"targetVersion": "2026-01-01", "publish": true, "note": "",
						 "acceptedReplacements": [], "keptInactiveConceptIds": ["1234567"]}
						""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.upgrade.changeCounts.INACTIVE_KEPT").value(1))
				.andExpect(jsonPath("$.upgrade.changeCounts.INACTIVATED").doesNotExist())
				.andExpect(jsonPath("$.upgrade.changeCounts.REPLACEMENT_DECLINED").value(2));

		mvc.perform(get("/api/value-sets/{id}/expansion", id).with(signedInAs("bob")))
				.andExpect(jsonPath("$.items[?(@.conceptId == '1234567')].active").value(false))
				.andExpect(jsonPath("$.items[?(@.conceptId == '195967001')].active").value(true));
		mvc.perform(get("/api/value-sets/{id}/download", id).with(signedInAs("bob")))
				.andExpect(content().string(containsString("1234567\tRetired (disorder)\tRetired asthma concept\t0\n")))
				.andExpect(content().string(containsString("195967001\tAsthma (disorder)\tAsthma\t1\n")));
		mvc.perform(get("/api/value-sets/{id}/download", id).param("format", "fhir").with(signedInAs("bob")))
				.andExpect(jsonPath("$.expansion.contains[?(@.code == '1234567')].inactive").value(true))
				.andExpect(jsonPath("$.expansion.contains[?(@.code == '195967001')].inactive").isEmpty());
		long upgradeId = jdbc.queryForObject("SELECT id FROM value_set_upgrade WHERE value_set_id = ?", Long.class, id);
		mvc.perform(get("/api/value-sets/{id}/upgrades/{upgradeId}/report", id, upgradeId).with(signedInAs("bob")))
				.andExpect(content().string(containsString(
						"INACTIVE_KEPT,\"1234567\",\"Retired asthma concept\",,,\"DUPLICATE\"\r\n")));
	}

	@Test
	void onlyAConceptThatIsInactiveCanBeKeptAsInactive() throws Exception {
		long id = insertSet("alice", "PRIVATE", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		retiredConceptHasReplacements();

		mvc.perform(post("/api/value-sets/{id}/upgrade", id).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"targetVersion": "2026-01-01", "publish": false, "acceptedReplacements": [],
						 "keptInactiveConceptIds": ["195967001"]}
						""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail")
						.value("195967001 is not an inactive concept in this value set, so it cannot be kept as one."));
	}

	@Test
	void aConceptKeptInAnEarlierUpgradeIsMarkedSoInTheNextPreview() throws Exception {
		long id = insertSet("alice", "PRIVATE", "MANUAL", "", OLD_RELEASE, OLD_ASTHMA_TERM, RETIRED);
		jdbc.update("UPDATE value_set_member SET active = FALSE WHERE value_set_id = ? AND concept_id = '1234567'", id);
		retiredConceptHasReplacements();

		mvc.perform(get("/api/value-sets/{id}/upgrade/preview", id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.inactivated[0].keptBefore").value(true));
	}

	@Test
	void aConceptIsShownInTheEditionLanguageWithItsModuleAndLanguageReferenceSetsNamed() throws Exception {
		String branch = "MAIN/SNOMEDCT-NO/2026-03-15";
		given(snowstorm.editionFor(branch)).willReturn(new Edition("SNOMEDCT-NO", "Norwegian Edition", "no",
				Map.of("no", "Norwegian", "en", "English"),
				Map.of("900000000000207008", "900000000000012004", "51000202101", "51000202101")));
		given(snowstorm.conceptDetails("389145006", branch, "no")).willReturn(new ConceptDetails("389145006",
				"Allergic asthma (disorder)", "allergisk astma", "no", true, "PRIMITIVE", "900000000000207008",
				"20020131", List.of(new ConceptDetails.Description("allergisk astma", "SYNONYM", "no",
						List.of("61000202103"), List.of(), "51000202101")),
				List.of(), List.of(), null, null, false, Map.of(), List.of()));
		given(snowstorm.activeConcepts(List.of("900000000000207008", "51000202101", "61000202103"), branch))
				.willReturn(new ExpansionPage(List.of(new Concept("900000000000207008", "SNOMED CT core", null, "en"),
						new Concept("51000202101", "Norwegian module", null, "en"),
						new Concept("61000202103", "Norwegian Bokmål language reference set", null, "en")), 3, 0, 3));

		mvc.perform(get("/api/concepts/389145006").param("branch", branch).with(signedInAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.preferredTerm").value("allergisk astma"))
				.andExpect(jsonPath("$.edition.language").value("no"))
				.andExpect(jsonPath("$.edition.name").value("Norwegian Edition"))
				.andExpect(jsonPath("$.international").value(true))
				.andExpect(jsonPath("$.moduleName").value("SNOMED CT core"))
				.andExpect(jsonPath("$.names.61000202103").value("Norwegian Bokmål language reference set"))
				.andExpect(jsonPath("$.descriptions[0].moduleId").value("51000202101"))
				.andExpect(jsonPath("$.internationalModules", contains("900000000000207008")));
	}

	private static final Concept DIABETES = new Concept("44054006", "Type 2 diabetes mellitus",
			"Type 2 diabetes mellitus (disorder)", "en");

	private static String identifierCheck(String branch, String entries) {
		return """
				{"branch": "%s", "entries": [%s]}
				""".formatted(branch, entries);
	}

	@Test
	void checkingImportedIdentifiersSortsThemIntoFoundInactiveAndRejected() throws Exception {
		given(snowstorm.conceptIdOfDescription("301484010", RELEASE)).willReturn(Optional.of("389145006"));
		given(snowstorm.activeConcepts(List.of("195967001", "233678006", "100000000", "10000006", "389145006"), RELEASE))
				.willReturn(pageOf(ASTHMA, CHILDHOOD_ASTHMA, ALLERGIC_ASTHMA));
		Map<String, List<String>> targets = new LinkedHashMap<>();
		targets.put("SAME_AS", List.of("233678006"));
		targets.put("REPLACED_BY", List.of("44054006"));
		given(snowstorm.inactivation("100000000", RELEASE))
				.willReturn(new Inactivation(false, "Retired concept", "OUTDATED", targets));
		given(snowstorm.inactivation("10000006", RELEASE))
				.willThrow(new SnowstormException("Concept 10000006 was not found in this code system.", true, null));
		given(snowstorm.activeConcepts(List.of("233678006", "44054006"), RELEASE))
				.willReturn(pageOf(CHILDHOOD_ASTHMA, DIABETES));

		mvc.perform(post("/api/identifier-checks").contentType(MediaType.APPLICATION_JSON)
				.content(identifierCheck(RELEASE, """
						{"identifier": "195967001", "term": "ASTHMA"},
						{"identifier": "233678006", "term": "Asthma in children"},
						{"identifier": " 195967001 "},
						{"identifier": "301484010"},
						{"identifier": "100000000"},
						{"identifier": "abc"},
						{"identifier": "195967002"},
						{"identifier": "100022"},
						{"identifier": "10000006"}
						"""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryCount").value(9))
				.andExpect(jsonPath("$.duplicateCount").value(1))
				.andExpect(jsonPath("$.found[*].conceptId", contains("195967001", "233678006", "389145006")))
				.andExpect(jsonPath("$.found[0].termDiffers").value(false))
				.andExpect(jsonPath("$.found[1].termDiffers").value(true))
				.andExpect(jsonPath("$.found[1].suppliedTerm").value("Asthma in children"))
				.andExpect(jsonPath("$.found[2].descriptionId").value("301484010"))
				.andExpect(jsonPath("$.inactive", hasSize(1)))
				.andExpect(jsonPath("$.inactive[0].term").value("Retired concept"))
				.andExpect(jsonPath("$.inactive[0].reason").value("OUTDATED"))
				.andExpect(jsonPath("$.inactive[0].replacements[*].conceptId", contains("44054006", "233678006")))
				.andExpect(jsonPath("$.inactive[0].replacements[1].alreadyInSet").value(true))
				.andExpect(jsonPath("$.rejected[*].identifier", contains("abc", "195967002", "100022", "10000006")))
				.andExpect(jsonPath("$.rejected[*].reason",
						contains("NOT_AN_IDENTIFIER", "INVALID_CHECK_DIGIT", "NOT_A_CONCEPT", "NOT_IN_RELEASE")));
	}

	@Test
	void aDescriptionThatIsNotInTheReleaseIsRejected() throws Exception {
		mvc.perform(post("/api/identifier-checks").contentType(MediaType.APPLICATION_JSON)
				.content(identifierCheck(RELEASE, "{\"identifier\": \"301484010\"}"))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rejected[0].identifier").value("301484010"))
				.andExpect(jsonPath("$.rejected[0].reason").value("NOT_IN_RELEASE"));
	}

	@Test
	void anIdentifierCheckNeedsAValidBranchAndSomeIdentifiers() throws Exception {
		mvc.perform(post("/api/identifier-checks").contentType(MediaType.APPLICATION_JSON)
				.content(identifierCheck("MAIN/../secrets", "{\"identifier\": \"195967001\"}"))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/identifier-checks").contentType(MediaType.APPLICATION_JSON)
				.content(identifierCheck(RELEASE, ""))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anEclExpressionCanFillAHandPickedListOnce() throws Exception {
		given(snowstorm.expand("< 195967001", RELEASE, 0, 2000))
				.willReturn(new ExpansionPage(List.of(CHILDHOOD_ASTHMA, ALLERGIC_ASTHMA), 2, 0, 2000));

		mvc.perform(post("/api/identifier-checks/ecl").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"branch": "%s", "ecl": "< 195967001"}
						""".formatted(RELEASE))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryCount").value(2))
				.andExpect(jsonPath("$.found[*].conceptId", contains("233678006", "389145006")))
				.andExpect(jsonPath("$.inactive", hasSize(0)));
	}

	@Test
	void anEclExpressionMatchingMoreThanAListCanHoldIsRefused() throws Exception {
		given(snowstorm.expand("<< 404684003", RELEASE, 0, 2000))
				.willReturn(new ExpansionPage(List.of(ASTHMA), 120000, 0, 2000));

		mvc.perform(post("/api/identifier-checks/ecl").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"branch": "%s", "ecl": "<< 404684003"}
						""".formatted(RELEASE))
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail", containsString("matches 120000 concepts")));
	}
}
