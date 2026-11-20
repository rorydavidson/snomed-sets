package org.snomed.valuesets.snowstorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SnowstormClientTests {

	private MockRestServiceServer server;
	private SnowstormClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new SnowstormClient(builder, new SnowstormProperties("https://snowstorm.test", "test-agent"));
	}

	@Test
	void mapsConceptsFromSnowstormResponse() {
		server.expect(requestTo(startsWith("https://snowstorm.test/MAIN/2026-11-01/concepts?ecl=")))
				.andExpect(queryParam("activeFilter", "true"))
				.andExpect(queryParam("offset", "50"))
				.andExpect(queryParam("limit", "25"))
				.andRespond(withSuccess("""
						{"items":[{"conceptId":"195967001","active":true,
						  "pt":{"term":"Asthma","lang":"en"},
						  "fsn":{"term":"Asthma (disorder)","lang":"en"}}],
						 "total":120,"limit":25,"offset":50}
						""", MediaType.APPLICATION_JSON));

		ExpansionPage page = client.expand("<< 195967001 |Asthma|", "MAIN/2026-11-01", 50, 25);

		assertThat(page.total()).isEqualTo(120);
		assertThat(page.offset()).isEqualTo(50);
		assertThat(page.items()).containsExactly(
				new ExpansionPage.Concept("195967001", "Asthma", "Asthma (disorder)", "en"));
		server.verify();
	}

	@Test
	void reportsRejectedEclAsCausedByTheRequest() {
		server.expect(requestTo(startsWith("https://snowstorm.test/MAIN/concepts"))).andRespond(withBadRequest());

		assertThatThrownBy(() -> client.expand("not ecl", "MAIN", 0, 50))
				.isInstanceOfSatisfying(SnowstormException.class,
						e -> assertThat(e.isCausedByRequest()).isTrue());
	}

	@Test
	void reportsNonJsonResponseAsSnowstormFailure() {
		server.expect(requestTo(startsWith("https://snowstorm.test/MAIN/concepts")))
				.andRespond(withSuccess("<html>Access Denied</html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> client.expand("<< 195967001", "MAIN", 0, 50))
				.isInstanceOfSatisfying(SnowstormException.class,
						e -> assertThat(e.isCausedByRequest()).isFalse());
	}

	@Test
	void refusesBranchPathsThatCouldEscapeTheSnowstormPath() {
		assertThatThrownBy(() -> client.expand("<< 195967001", "MAIN/../admin", 0, 50))
				.isInstanceOf(SnowstormException.class);
		assertThat(SnowstormClient.isValidBranch("MAIN/SNOMEDCT-BE/2026-03-15")).isTrue();
		assertThat(SnowstormClient.isValidBranch("OTHER")).isFalse();
	}

	@Test
	void listsCodeSystemsWithTheirBranchAndLatestVersion() {
		server.expect(requestTo("https://snowstorm.test/codesystems")).andRespond(withSuccess("""
				{"items":[
				  {"shortName":"SNOMEDCT","branchPath":"MAIN","latestVersion":{"version":"2026-01-01","branchPath":"MAIN/2026-01-01"}},
				  {"shortName":"SNOMEDCT-BE","name":"Belgian Edition","branchPath":"MAIN/SNOMEDCT-BE"}]}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.codeSystems()).containsExactly(
				new CodeSystem("SNOMEDCT", "SNOMEDCT", "MAIN", "2026-01-01", "MAIN/2026-01-01"),
				new CodeSystem("SNOMEDCT-BE", "Belgian Edition", "MAIN/SNOMEDCT-BE", null, null));
	}

	@Test
	void conceptDetailsKeepOnlyActiveDescriptionsAndActiveInferredRelationships() {
		server.expect(requestTo("https://snowstorm.test/browser/MAIN/SNOMEDCT-BE/concepts/233678006"))
				.andRespond(withSuccess("""
						{"conceptId":"233678006","active":true,"definitionStatus":"PRIMITIVE",
						 "moduleId":"900000000000207008","effectiveTime":"20020131",
						 "fsn":{"term":"Childhood asthma (disorder)"},"pt":{"term":"Childhood asthma"},
						 "descriptions":[
						   {"term":"Childhood asthma","type":"SYNONYM","lang":"en","active":true,
						    "acceptabilityMap":{"900000000000509007":"PREFERRED","900000000000508004":"ACCEPTABLE",
						                        "21000172104":"PREFERRED"}},
						   {"term":"Asthma of childhood","type":"SYNONYM","lang":"en","active":false}],
						 "relationships":[
						   {"active":true,"typeId":"116680003","characteristicType":"INFERRED_RELATIONSHIP","groupId":0,
						    "type":{"conceptId":"116680003","pt":{"term":"Is a"}},
						    "target":{"conceptId":"195967001","pt":{"term":"Asthma"}}},
						   {"active":true,"typeId":"246454002","characteristicType":"INFERRED_RELATIONSHIP","groupId":1,
						    "type":{"conceptId":"246454002","pt":{"term":"Occurrence"}},
						    "target":{"conceptId":"255398004","pt":{"term":"Childhood"}}},
						   {"active":false,"typeId":"116680003","characteristicType":"INFERRED_RELATIONSHIP","groupId":0,
						    "type":{"conceptId":"116680003","pt":{"term":"Is a"}},
						    "target":{"conceptId":"1","pt":{"term":"Retired parent"}}},
						   {"active":true,"typeId":"116680003","characteristicType":"STATED_RELATIONSHIP","groupId":0,
						    "type":{"conceptId":"116680003","pt":{"term":"Is a"}},
						    "target":{"conceptId":"2","pt":{"term":"Stated parent"}}}]}
						""", MediaType.APPLICATION_JSON));

		ConceptDetails details = client.conceptDetails("233678006", "MAIN/SNOMEDCT-BE");

		assertThat(details.preferredTerm()).isEqualTo("Childhood asthma");
		assertThat(details.descriptions())
				.containsExactly(new ConceptDetails.Description("Childhood asthma", "SYNONYM", "en",
						List.of("21000172104", "en-US"), List.of("en-GB"), null));
		assertThat(details.parents())
				.containsExactly(new ConceptDetails.Relationship("116680003", "Is a", "195967001", "Asthma", 0));
		assertThat(details.attributes())
				.containsExactly(new ConceptDetails.Relationship("246454002", "Occurrence", "255398004", "Childhood", 1));
	}

	@Test
	void anUnknownConceptIsReportedAsCausedByTheRequest() {
		server.expect(requestTo("https://snowstorm.test/browser/MAIN/concepts/999999999"))
				.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
						.withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.conceptDetails("999999999", "MAIN"))
				.isInstanceOfSatisfying(SnowstormException.class, e -> {
					assertThat(e.isCausedByRequest()).isTrue();
					assertThat(e).hasMessage("Concept 999999999 was not found in this code system.");
				});
	}

	@Test
	void readsTheInactivationReasonAndHistoricalAssociations() {
		server.expect(requestTo("https://snowstorm.test/browser/MAIN/2026-01-01/concepts/1234567"))
				.andRespond(withSuccess("""
						{"conceptId":"1234567","active":false,"inactivationIndicator":"DUPLICATE",
						 "associationTargets":{"SAME_AS":["233678006"]},
						 "fsn":{"term":"Retired (disorder)","lang":"en"},"pt":{"term":"Retired","lang":"en"}}
						""", MediaType.APPLICATION_JSON));

		Inactivation inactivation = client.inactivation("1234567", "MAIN/2026-01-01");

		assertThat(inactivation.active()).isFalse();
		assertThat(inactivation.indicator()).isEqualTo("DUPLICATE");
		assertThat(inactivation.associationTargets()).containsEntry("SAME_AS", List.of("233678006"));
		server.verify();
	}

	@Test
	void asksForSeveralConceptsByIdentifierSoInactiveOnesAreLeftOutRatherThanRefused() {
		server.expect(requestTo(
				"https://snowstorm.test/MAIN/2026-07-01/concepts?conceptIds=195967001&conceptIds=90653005&activeFilter=true&offset=0&limit=2"))
				.andRespond(withSuccess("""
						{"items":[{"conceptId":"195967001","active":true,"pt":{"term":"Asthma","lang":"en"},
						  "fsn":{"term":"Asthma (disorder)","lang":"en"}}],"total":1,"limit":2,"offset":0}
						""", MediaType.APPLICATION_JSON));

		ExpansionPage page = client.activeConcepts(List.of("195967001", "90653005"), "MAIN/2026-07-01");

		assertThat(page.items()).extracting(ExpansionPage.Concept::conceptId).containsExactly("195967001");
		server.verify();
	}

	@Test
	void listsTheReleasesOfACodeSystemNewestFirst() {
		server.expect(requestTo("https://snowstorm.test/codesystems/SNOMEDCT/versions"))
				.andRespond(withSuccess("""
						{"items":[{"version":"2026-01-01","branchPath":"MAIN/2026-01-01"},
						          {"version":"2026-07-01","branchPath":"MAIN/2026-07-01"},
						          {"version":"2025-01-01","branchPath":"../escape"}]}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.codeSystemVersions("SNOMEDCT")).containsExactly(
				new CodeSystemVersion("2026-07-01", "MAIN/2026-07-01"),
				new CodeSystemVersion("2026-01-01", "MAIN/2026-01-01"));
		assertThatThrownBy(() -> client.codeSystemVersions("../admin")).isInstanceOf(SnowstormException.class);
		server.verify();
	}

	@Test
	void findsTheModuleThatNamesAnExtensionEdition() {
		server.expect(requestTo("https://snowstorm.test/codesystems/SNOMEDCT-NO"))
				.andRespond(withSuccess("""
						{"shortName":"SNOMEDCT-NO","modules":[
						  {"conceptId":"900000000000012004","moduleId":"900000000000012004"},
						  {"conceptId":"57091000202101","moduleId":"51000202101"},
						  {"conceptId":"51000202101","moduleId":"51000202101"},
						  {"conceptId":"900000000000207008","moduleId":"900000000000012004"}]}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.editionModuleId("SNOMEDCT-NO")).isEqualTo("51000202101");
		assertThat(client.editionModuleId("SNOMEDCT")).isEqualTo("900000000000207008");
		server.verify();
	}

	@Test
	void asksForTermsInTheEditionLanguageWithEnglishAfterIt() {
		server.expect(requestTo("https://snowstorm.test/browser/MAIN/SNOMEDCT-NO/2026-03-15/concepts/389145006"))
				.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Accept-Language",
						"no,en-X-900000000000509007,en"))
				.andRespond(withSuccess("""
						{"conceptId":"389145006","active":true,"moduleId":"900000000000207008",
						 "pt":{"term":"allergisk astma","lang":"no"},"fsn":{"term":"Allergic asthma (disorder)","lang":"en"},
						 "descriptions":[],"relationships":[]}
						""", MediaType.APPLICATION_JSON));

		ConceptDetails details = client.conceptDetails("389145006", "MAIN/SNOMEDCT-NO/2026-03-15", "no");

		assertThat(details.preferredTerm()).isEqualTo("allergisk astma");
		assertThat(details.preferredTermLanguage()).isEqualTo("no");
		server.verify();
	}

	@Test
	void findsTheEditionOfAReleaseBranchWithItsLanguageAndModules() {
		server.expect(requestTo("https://snowstorm.test/codesystems"))
				.andRespond(withSuccess("""
						{"items":[
						  {"shortName":"SNOMEDCT","branchPath":"MAIN","languages":{"en":"English"},
						   "modules":[{"conceptId":"900000000000207008","moduleId":"900000000000012004"}]},
						  {"shortName":"SNOMEDCT-NO","name":"Norwegian Edition","branchPath":"MAIN/SNOMEDCT-NO",
						   "languages":{"no":"Norwegian","en":"English"},
						   "modules":[{"conceptId":"51000202101","moduleId":"51000202101"},
						              {"conceptId":"900000000000207008","moduleId":"900000000000012004"}]}]}
						""", MediaType.APPLICATION_JSON));

		Edition edition = client.editionFor("MAIN/SNOMEDCT-NO/2026-03-15");

		assertThat(edition.shortName()).isEqualTo("SNOMEDCT-NO");
		assertThat(edition.language()).isEqualTo("no");
		assertThat(edition.isInternational("900000000000207008")).isTrue();
		assertThat(edition.isInternational("51000202101")).isFalse();
		server.verify();
	}

	@Test
	void sendsLongExpressionsAndConceptFiltersInTheRequestBody() {
		server.expect(requestTo("https://snowstorm.test/MAIN/2026-01-01/concepts/search"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.eclFilter").value("< (195967001 OR 233678006)"))
				.andExpect(jsonPath("$.conceptIds[0]").value("195967001"))
				.andExpect(jsonPath("$.activeFilter").value(true))
				.andRespond(withSuccess("""
						{"items":[{"conceptId":"233678006","pt":{"term":"Childhood asthma","lang":"en"}}],"total":1}
						""", MediaType.APPLICATION_JSON));

		ExpansionPage page = client.expandLong("< (195967001 OR 233678006)", "MAIN/2026-01-01", 0, 100,
				List.of("195967001", "233678006"));

		assertThat(page.items()).extracting(ExpansionPage.Concept::conceptId).containsExactly("233678006");
		server.verify();
	}

	@Test
	void readsOnlyActiveInferredIsARelationshipsAsParents() {
		server.expect(requestTo("https://snowstorm.test/browser/MAIN/2026-01-01/concepts/bulk-load"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.conceptIds[0]").value("233678006"))
				.andRespond(withSuccess("""
						[{"conceptId":"233678006","active":true,"relationships":[
						  {"active":true,"typeId":"116680003","characteristicType":"INFERRED_RELATIONSHIP","groupId":0,
						   "target":{"conceptId":"195967001"}},
						  {"active":true,"typeId":"116680003","characteristicType":"STATED_RELATIONSHIP","groupId":0,
						   "target":{"conceptId":"50043002"}},
						  {"active":false,"typeId":"116680003","characteristicType":"INFERRED_RELATIONSHIP","groupId":0,
						   "target":{"conceptId":"64572001"}},
						  {"active":true,"typeId":"363698007","characteristicType":"INFERRED_RELATIONSHIP","groupId":1,
						   "target":{"conceptId":"39607008"}}]}]
						""", MediaType.APPLICATION_JSON));

		assertThat(client.inferredParents(List.of("233678006"), "MAIN/2026-01-01"))
				.containsExactlyEntriesOf(java.util.Map.of("233678006", List.of("195967001")));
		server.verify();
	}

	@Test
	void findsDescriptionsWithTheirConceptsInTheEditionsLanguages() {
		server.expect(requestTo(startsWith("https://snowstorm.test/browser/MAIN/SNOMEDCT-NO/2026-03-15/descriptions?")))
				// Encoded, so a term cannot add parameters of its own.
				.andExpect(queryParam("term", "heart%20attack%26limit%3D9"))
				.andExpect(queryParam("active", "true"))
				.andExpect(queryParam("conceptActive", "true"))
				.andExpect(queryParam("language", "no", "en"))
				.andExpect(queryParam("limit", "50"))
				.andRespond(withSuccess("""
						{"items":[{"term":"Heart attack","active":true,"languageCode":"en","module":"900000000000207008",
						  "concept":{"conceptId":"22298006","active":true,
						    "pt":{"term":"Myocardial infarction","lang":"en"},
						    "fsn":{"term":"Myocardial infarction (disorder)","lang":"en"}}}],
						 "totalElements":1}
						""", MediaType.APPLICATION_JSON));

		List<DescriptionMatch> matches = client.descriptionsMatching("heart attack&limit=9", "MAIN/SNOMEDCT-NO/2026-03-15",
				List.of("no", "en"), 50);

		assertThat(matches).containsExactly(new DescriptionMatch("Heart attack", "en",
				new ExpansionPage.Concept("22298006", "Myocardial infarction", "Myocardial infarction (disorder)", "en")));
		server.verify();
	}

	@Test
	void readsTheActiveNamesOfConceptsAndLeavesOutDefinitions() {
		server.expect(requestTo(startsWith("https://snowstorm.test/MAIN/2026-01-01/descriptions?")))
				.andExpect(queryParam("conceptIds", "22298006", "195967001"))
				.andRespond(withSuccess("""
						{"items":[
						  {"conceptId":"22298006","term":"Heart attack","lang":"en","type":"SYNONYM","active":true},
						  {"conceptId":"22298006","term":"Heart attack, NOS","lang":"en","type":"SYNONYM","active":false},
						  {"conceptId":"195967001","term":"Asthma (disorder)","lang":"en","type":"FSN","active":true},
						  {"conceptId":"195967001","term":"A long text about asthma.","lang":"en","type":"DEFINITION","active":true}],
						 "total":4,"limit":1000,"offset":0}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.termsOf(List.of("22298006", "195967001"), "MAIN/2026-01-01")).containsExactly(
				new ConceptTerm("22298006", "Heart attack", "en"),
				new ConceptTerm("195967001", "Asthma (disorder)", "en"));
		server.verify();
	}
}
