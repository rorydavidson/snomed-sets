package org.snomed.valuesets.snowstorm;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class SnowstormClient {

	// Branch paths are placed in the request URL, so only the shape Snowstorm uses is accepted.
	public static final String BRANCH_PATH_REGEX = "MAIN(/[A-Za-z0-9][A-Za-z0-9_.-]*)*";

	private static final Pattern BRANCH_PATH = Pattern.compile(BRANCH_PATH_REGEX);

	private final RestClient restClient;

	public SnowstormClient(RestClient.Builder builder, SnowstormProperties properties) {
		this.restClient = builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en")
				.build();
	}

	public static boolean isValidBranch(String branch) {
		return branch != null && BRANCH_PATH.matcher(branch).matches();
	}

	public List<CodeSystem> codeSystems() {
		SnowstormCodeSystems response;
		try {
			response = restClient.get().uri("/codesystems").retrieve().body(SnowstormCodeSystems.class);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not list its code systems.", false, e);
		}
		if (response == null || response.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return response.items().stream()
				.filter(item -> isValidBranch(item.branchPath()))
				.map(item -> new CodeSystem(item.shortName(), item.name() == null ? item.shortName() : item.name(),
						item.branchPath(), item.latestVersion() == null ? null : item.latestVersion().version()))
				.toList();
	}

	public ConceptDetails conceptDetails(String conceptId, String branch) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		BrowserConcept concept;
		try {
			concept = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment("browser")
							.pathSegment(branch.split("/"))
							.pathSegment("concepts", conceptId)
							.build())
					.retrieve()
					.body(BrowserConcept.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected
					? "Concept " + conceptId + " was not found in this code system."
					: "Snowstorm returned an error.", rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		if (concept == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return concept.toDetails();
	}

	/** The active concepts matching an ECL expression. */
	public ExpansionPage expand(String ecl, String branch, int offset, int limit) {
		return concepts("ecl", ecl, branch, offset, limit);
	}

	/** The active concepts whose descriptions match the words in a search term. */
	public ExpansionPage search(String term, String branch, int limit) {
		return concepts("term", term, branch, 0, limit);
	}

	private ExpansionPage concepts(String filterName, String filterValue, String branch, int offset, int limit) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		SnowstormConceptPage page;
		try {
			page = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment(branch.split("/"))
							.pathSegment("concepts")
							.queryParam(filterName, "{value}")
							.queryParam("activeFilter", true)
							.queryParam("offset", offset)
							.queryParam("limit", limit)
							.build(filterValue))
					.retrieve()
					.body(SnowstormConceptPage.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			String message = rejected
					? "Snowstorm rejected the request. Check the ECL expression and branch."
					: "Snowstorm returned an error.";
			throw new SnowstormException(message, rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		if (page == null || page.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		List<ExpansionPage.Concept> concepts = page.items().stream()
				.map(item -> new ExpansionPage.Concept(item.conceptId(), termOf(item.pt()), termOf(item.fsn())))
				.toList();
		return new ExpansionPage(concepts, page.total(), offset, limit);
	}

	private static String termOf(SnowstormTerm term) {
		return term == null ? null : term.term();
	}

	record SnowstormConceptPage(List<SnowstormConcept> items, long total) {
	}

	record SnowstormConcept(String conceptId, SnowstormTerm pt, SnowstormTerm fsn) {
	}

	record SnowstormTerm(String term) {
	}

	record BrowserConcept(String conceptId, SnowstormTerm fsn, SnowstormTerm pt, boolean active,
			String definitionStatus, String moduleId, String effectiveTime, List<BrowserDescription> descriptions,
			List<BrowserRelationship> relationships) {

		private static final String IS_A = "116680003";

		ConceptDetails toDetails() {
			List<ConceptDetails.Description> activeDescriptions = orEmpty(descriptions).stream()
					.filter(BrowserDescription::active)
					.map(description -> new ConceptDetails.Description(description.term(), description.type(),
							description.lang(), description.acceptabilityMap() != null
									&& description.acceptabilityMap().containsValue("PREFERRED")))
					.toList();
			List<BrowserRelationship> inferred = orEmpty(relationships).stream()
					.filter(relationship -> relationship.active()
							&& "INFERRED_RELATIONSHIP".equals(relationship.characteristicType())
							&& relationship.target() != null)
					.toList();
			return new ConceptDetails(conceptId, termOf(fsn), termOf(pt), active, definitionStatus, moduleId,
					effectiveTime, activeDescriptions,
					inferred.stream().filter(r -> IS_A.equals(r.typeId())).map(BrowserRelationship::toRelationship).toList(),
					inferred.stream().filter(r -> !IS_A.equals(r.typeId())).map(BrowserRelationship::toRelationship).toList());
		}

		private static <T> List<T> orEmpty(List<T> list) {
			return list == null ? List.of() : list;
		}
	}

	record BrowserDescription(String term, String type, String lang, boolean active,
			java.util.Map<String, String> acceptabilityMap) {
	}

	record BrowserRelationship(boolean active, String typeId, String characteristicType, int groupId,
			SnowstormConcept type, SnowstormConcept target) {

		ConceptDetails.Relationship toRelationship() {
			return new ConceptDetails.Relationship(type == null ? null : termOf(type.pt()), target.conceptId(),
					termOf(target.pt()), groupId);
		}
	}

	record SnowstormCodeSystems(List<SnowstormCodeSystem> items) {
	}

	record SnowstormCodeSystem(String shortName, String name, String branchPath, SnowstormCodeSystemVersion latestVersion) {
	}

	record SnowstormCodeSystemVersion(String version) {
	}
}
