package org.snomed.valuesets.snowstorm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
				  {"shortName":"SNOMEDCT","branchPath":"MAIN","latestVersion":{"version":"2026-01-01"}},
				  {"shortName":"SNOMEDCT-BE","name":"Belgian Edition","branchPath":"MAIN/SNOMEDCT-BE"}]}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.codeSystems()).containsExactly(
				new CodeSystem("SNOMEDCT", "SNOMEDCT", "MAIN", "2026-01-01"),
				new CodeSystem("SNOMEDCT-BE", "Belgian Edition", "MAIN/SNOMEDCT-BE", null));
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
						    "acceptabilityMap":{"900000000000509007":"PREFERRED"}},
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
				.containsExactly(new ConceptDetails.Description("Childhood asthma", "SYNONYM", "en", true));
		assertThat(details.parents())
				.containsExactly(new ConceptDetails.Relationship("Is a", "195967001", "Asthma", 0));
		assertThat(details.attributes())
				.containsExactly(new ConceptDetails.Relationship("Occurrence", "255398004", "Childhood", 1));
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
}
