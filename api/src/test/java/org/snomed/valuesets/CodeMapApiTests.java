package org.snomed.valuesets;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.DescriptionMatch;
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
class CodeMapApiTests {

	@Container
	@ServiceConnection
	static MariaDBContainer mariadb = new MariaDBContainer("mariadb:11.8");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	SnowstormClient snowstorm;

	private static final String RELEASE = "MAIN/2026-01-01";

	private static final Concept ASTHMA = new Concept("195967001", "Asthma", "Asthma (disorder)", "en");
	private static final Concept COLD_FINDING = new Concept("84162001", "Cold sensation quality",
			"Cold sensation quality (qualifier value)", "en");
	private static final Concept COMMON_COLD = new Concept("82272006", "Common cold", "Common cold (disorder)", "en");

	private static final String NEW_MAP = """
			{"details": {"name": "Ward picklist", "description": "", "localSystemName": "Ward diagnoses"},
			 "rows": [{"code": "A1", "term": "Asthma"}, {"code": "a1", "term": "Cold"}, {"code": "Z9", "term": "Not in SNOMED"}]}
			""";

	@BeforeEach
	void snowstormHasTheInternationalEdition() {
		given(snowstorm.codeSystems()).willReturn(List.of(
				new CodeSystem("SNOMEDCT", "International Edition", "MAIN", "2026-01-01", RELEASE)));
		given(snowstorm.descriptionsMatching(anyString(), anyString(), anyCollection(), anyInt())).willReturn(List.of());
	}

	@AfterEach
	void removeMapsCreatedByTests() {
		jdbc.update("DELETE FROM code_map");
	}

	private static RequestPostProcessor signedInAs(String subject) {
		return oidcLogin().idToken(token -> token.subject(subject).claim("name", "Person " + subject));
	}

	private long createMap(String subject) throws Exception {
		mvc.perform(post("/api/maps").contentType(MediaType.APPLICATION_JSON).content(NEW_MAP)
				.with(signedInAs(subject)).with(csrf()))
				.andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT id FROM code_map WHERE owner_subject = ?", Long.class, subject);
	}

	private long rowId(long mapId, String code) {
		return jdbc.queryForObject("SELECT id FROM code_map_row WHERE map_id = ? AND source_code = ?", Long.class,
				mapId, code);
	}

	private void matchAllTerms(long mapId) throws Exception {
		List<Long> ids = jdbc.queryForList("SELECT id FROM code_map_row WHERE map_id = ?", Long.class, mapId);
		mvc.perform(post("/api/maps/" + mapId + "/term-matches").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rowIds\": " + ids + "}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk());
	}

	@Test
	void createdMapIsBuiltOnTheLatestReleaseWithItsRowsUnmapped() throws Exception {
		mvc.perform(post("/api/maps").contentType(MediaType.APPLICATION_JSON).content(NEW_MAP)
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Ward picklist"))
				.andExpect(jsonPath("$.direction").value("TO_SNOMED"))
				.andExpect(jsonPath("$.branch").value(RELEASE))
				.andExpect(jsonPath("$.codeSystemVersion").value("2026-01-01"))
				.andExpect(jsonPath("$.counts.total").value(3))
				.andExpect(jsonPath("$.counts.unmapped").value(3));
	}

	@Test
	void codesThatDifferOnlyInCaseAreDifferentCodes() throws Exception {
		long id = createMap("alice");

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[*].code", contains("A1", "a1", "Z9")));
	}

	@Test
	void aRepeatedCodeIsRefusedAndNamed() throws Exception {
		mvc.perform(post("/api/maps").contentType(MediaType.APPLICATION_JSON).content("""
				{"details": {"name": "Ward picklist", "description": "", "localSystemName": "Ward diagnoses"},
				 "rows": [{"code": "A1", "term": "Asthma"}, {"code": "A1", "term": "Asthma again"}]}
				""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail", containsString("A1")));
	}

	@Test
	void mapsAreOnlyForPeopleSignedIn() throws Exception {
		mvc.perform(get("/api/maps")).andExpect(status().isUnauthorized());
	}

	@Test
	void anotherPersonCannotSeeOrChangeAMap() throws Exception {
		long id = createMap("alice");

		mvc.perform(get("/api/maps").with(signedInAs("bob"))).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/maps/" + id).with(signedInAs("bob"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("bob"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/maps/" + id + "/download").with(signedInAs("bob"))).andExpect(status().isNotFound());
		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"noMap\": true}").with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/maps/" + id).with(signedInAs("bob")).with(csrf())).andExpect(status().isNotFound());
	}

	@Test
	void aRowOfAnotherMapCannotBeChangedThroughYourOwn() throws Exception {
		long alices = createMap("alice");
		long bobs = createMap("bob");

		mvc.perform(put("/api/maps/" + bobs + "/rows/" + rowId(alices, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"noMap\": true}").with(signedInAs("bob")).with(csrf()))
				.andExpect(status().isNotFound());
	}

	@Test
	void anExactTermMatchBecomesASuggestionNotAMap() throws Exception {
		long id = createMap("alice");
		given(snowstorm.descriptionsMatching(eq("asthma"), eq(RELEASE), anyCollection(), anyInt())).willReturn(List.of(
				new DescriptionMatch("Asthma", "en", ASTHMA),
				new DescriptionMatch("Asthma attack", "en", new Concept("708038006", "Acute asthma", "Acute asthma (disorder)", "en"))));

		matchAllTerms(id);

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].status").value("SUGGESTED"))
				.andExpect(jsonPath("$[0].targets", hasSize(1)))
				.andExpect(jsonPath("$[0].targets[0].code").value("195967001"))
				.andExpect(jsonPath("$[0].targets[0].relationship").value("EQUIVALENT"))
				.andExpect(jsonPath("$[0].targets[0].origin").value("EXACT_TERM"))
				.andExpect(jsonPath("$[2].status").value("UNMAPPED"))
				.andExpect(jsonPath("$[2].termChecked").value(true));
		mvc.perform(get("/api/maps/" + id).with(signedInAs("alice")))
				.andExpect(jsonPath("$.counts.suggested").value(1))
				.andExpect(jsonPath("$.counts.mapped").value(0));
	}

	@Test
	void onlySingleSuggestionsAreAcceptedInOneGo() throws Exception {
		long id = createMap("alice");
		given(snowstorm.descriptionsMatching(eq("asthma"), eq(RELEASE), anyCollection(), anyInt()))
				.willReturn(List.of(new DescriptionMatch("Asthma", "en", ASTHMA)));
		// The same word names two different things, so the owner has to choose.
		given(snowstorm.descriptionsMatching(eq("cold"), eq(RELEASE), anyCollection(), anyInt())).willReturn(List.of(
				new DescriptionMatch("Cold", "en", COLD_FINDING), new DescriptionMatch("Cold", "en", COMMON_COLD)));
		matchAllTerms(id);

		mvc.perform(post("/api/maps/" + id + "/accepted-suggestions").with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.accepted").value(1));

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].status").value("MAPPED"))
				.andExpect(jsonPath("$[1].status").value("SUGGESTED"))
				.andExpect(jsonPath("$[1].targets", hasSize(2)));
	}

	@Test
	void aRowAlreadyMappedIsNotMatchedAgain() throws Exception {
		long id = createMap("alice");
		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"noMap\": true}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk());

		matchAllTerms(id);

		verify(snowstorm, never()).descriptionsMatching(eq("asthma"), anyString(), anyCollection(), anyInt());
		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].status").value("NO_MAP"));
	}

	@Test
	void aTermSnowstormCannotSearchForLeavesTheRowUnmapped() throws Exception {
		long id = createMap("alice");
		given(snowstorm.descriptionsMatching(eq("asthma"), anyString(), anyCollection(), anyInt()))
				.willThrow(new SnowstormException("Snowstorm rejected the search.", true, null));

		matchAllTerms(id);

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].status").value("UNMAPPED"))
				.andExpect(jsonPath("$[0].termChecked").value(true));
	}

	@Test
	void whenSnowstormIsDownNothingIsMarkedAsChecked() throws Exception {
		long id = createMap("alice");
		given(snowstorm.descriptionsMatching(anyString(), anyString(), anyCollection(), anyInt()))
				.willThrow(new SnowstormException("Snowstorm could not be reached.", false, null));

		mvc.perform(post("/api/maps/" + id + "/term-matches").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rowIds\": [" + rowId(id, "A1") + "]}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadGateway());

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[0].termChecked").value(false));
	}

	@Test
	void chosenTargetsTakeTheirTermsFromSnowstorm() throws Exception {
		long id = createMap("alice");
		given(snowstorm.activeConcepts(anyList(), eq(RELEASE)))
				.willReturn(new ExpansionPage(List.of(COMMON_COLD, ASTHMA), 2, 0, 2));

		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"targets": [{"conceptId": "195967001"},
						             {"conceptId": "82272006", "relationship": "TARGET_BROADER"}],
						 "note": "Checked with the ward"}
						""").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("MAPPED"))
				.andExpect(jsonPath("$.note").value("Checked with the ward"))
				.andExpect(jsonPath("$.targets[*].code", contains("195967001", "82272006")))
				.andExpect(jsonPath("$.targets[0].term").value("Asthma"))
				.andExpect(jsonPath("$.targets[0].origin").value("MANUAL"))
				.andExpect(jsonPath("$.targets[1].relationship").value("TARGET_BROADER"));
	}

	@Test
	void aTargetThatIsNotAnActiveConceptIsRefused() throws Exception {
		long id = createMap("alice");
		given(snowstorm.activeConcepts(anyList(), eq(RELEASE))).willReturn(new ExpansionPage(List.of(), 0, 0, 1));

		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targets\": [{\"conceptId\": \"195967001\"}]}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void changingTheTargetsKeepsOnesThatStayAndClearingThemUnmapsTheRow() throws Exception {
		long id = createMap("alice");
		long row = rowId(id, "A1");
		given(snowstorm.descriptionsMatching(eq("asthma"), eq(RELEASE), anyCollection(), anyInt()))
				.willReturn(List.of(new DescriptionMatch("Asthma", "en", ASTHMA)));
		matchAllTerms(id);
		given(snowstorm.activeConcepts(anyList(), eq(RELEASE)))
				.willReturn(new ExpansionPage(List.of(ASTHMA, COMMON_COLD), 2, 0, 2));

		mvc.perform(put("/api/maps/" + id + "/rows/" + row).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targets\": [{\"conceptId\": \"195967001\"}, {\"conceptId\": \"82272006\"}]}")
				.with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.status").value("MAPPED"))
				// The accepted suggestion still says where it came from.
				.andExpect(jsonPath("$.targets[0].origin").value("EXACT_TERM"))
				.andExpect(jsonPath("$.targets[1].origin").value("MANUAL"));

		mvc.perform(put("/api/maps/" + id + "/rows/" + row).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targets\": []}").with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.status").value("UNMAPPED"))
				.andExpect(jsonPath("$.targets", hasSize(0)));
	}

	@Test
	void moreCodesCanBeAddedButNotOnesAlreadyThere() throws Exception {
		long id = createMap("alice");

		mvc.perform(post("/api/maps/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rows\": [{\"code\": \"B2\", \"term\": \"Bronchitis\"}]}").with(signedInAs("alice")).with(csrf()))
				.andExpect(jsonPath("$.counts.total").value(4));
		mvc.perform(post("/api/maps/" + id + "/rows").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rows\": [{\"code\": \"Z9\", \"term\": \"Again\"}]}").with(signedInAs("alice")).with(csrf()))
				.andExpect(status().isBadRequest());

		mvc.perform(get("/api/maps/" + id + "/rows").with(signedInAs("alice")))
				.andExpect(jsonPath("$[*].code", contains("A1", "a1", "Z9", "B2")));
	}

	@Test
	void downloadLeavesOutSuggestionsNobodyAccepted() throws Exception {
		long id = createMap("alice");
		given(snowstorm.descriptionsMatching(eq("cold"), eq(RELEASE), anyCollection(), anyInt())).willReturn(List.of(
				new DescriptionMatch("Cold", "en", COLD_FINDING), new DescriptionMatch("Cold", "en", COMMON_COLD)));
		matchAllTerms(id);
		given(snowstorm.activeConcepts(anyList(), eq(RELEASE))).willReturn(new ExpansionPage(List.of(ASTHMA), 1, 0, 1));
		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "A1")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targets\": [{\"conceptId\": \"195967001\"}]}").with(signedInAs("alice")).with(csrf()));
		mvc.perform(put("/api/maps/" + id + "/rows/" + rowId(id, "Z9")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"noMap\": true}").with(signedInAs("alice")).with(csrf()));

		mvc.perform(get("/api/maps/" + id + "/download").param("format", "TSV").with(signedInAs("alice")))
				.andExpect(header().string("Content-Disposition", containsString("ward-picklist-draft.tsv")))
				.andExpect(content().string("""
						Source code\tSource term\tTarget SNOMED CT ID\tTarget term\tRelationship\tStatus\tNote
						A1\tAsthma\t195967001\tAsthma\tEQUIVALENT\tMAPPED\t
						a1\tCold\t\t\t\tUNMAPPED\t
						Z9\tNot in SNOMED\t\t\t\tNO_MAP\t
						"""));
	}

	@Test
	void deletingAMapRemovesItsRows() throws Exception {
		long id = createMap("alice");

		mvc.perform(delete("/api/maps/" + id).with(signedInAs("alice")).with(csrf())).andExpect(status().isNoContent());

		mvc.perform(get("/api/maps/" + id).with(signedInAs("alice"))).andExpect(status().isNotFound());
		org.assertj.core.api.Assertions.assertThat(
				jdbc.queryForObject("SELECT COUNT(*) FROM code_map_row WHERE map_id = ?", Integer.class, id)).isZero();
	}
}
