package org.snomed.valuesets.snowstorm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class SnowstormClient {

	// Branch paths are placed in the request URL, so only the shape Snowstorm uses is accepted.
	// Terms are asked for in US English, by its language reference set. Plain "en" makes
	// Snowstorm prefer British terms, and the GPS download format is defined as en-US.
	private static final String US_ENGLISH = "en-X-900000000000509007,en";

	public static final String BRANCH_PATH_REGEX = "MAIN(/[A-Za-z0-9][A-Za-z0-9_.-]*)*";

	private static final Pattern BRANCH_PATH = Pattern.compile(BRANCH_PATH_REGEX);

	private final RestClient restClient;

	public SnowstormClient(RestClient.Builder builder, SnowstormProperties properties) {
		this.restClient = builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, US_ENGLISH)
				.build();
	}

	public static boolean isValidBranch(String branch) {
		return branch != null && BRANCH_PATH.matcher(branch).matches();
	}

	/** The module of the International Edition, which Snowstorm leaves implicit. */
	static final String INTERNATIONAL_MODULE = "900000000000207008";
	private static final String MODEL_MODULE = "900000000000012004";

	/**
	 * The module that names an edition in a SNOMED CT version URI, for example 51000202101 for
	 * the Norwegian Edition, or null when it cannot be told apart. Snowstorm gives it only when
	 * configured to, so otherwise it is taken as the one module of the edition's own that is its
	 * own module, as an extension's root module is.
	 */
	public String editionModuleId(String shortName) {
		if ("SNOMEDCT".equals(shortName)) {
			return INTERNATIONAL_MODULE;
		}
		if (shortName == null || !shortName.matches("[A-Za-z0-9_-]{1,100}")) {
			throw new SnowstormException("Code system name is not valid: " + shortName, true, null);
		}
		SnowstormCodeSystemDetails details;
		try {
			details = restClient.get()
					.uri(uriBuilder -> uriBuilder.pathSegment("codesystems", shortName).build())
					.retrieve()
					.body(SnowstormCodeSystemDetails.class);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not describe " + shortName + ".", false, e);
		}
		if (details == null) {
			return null;
		}
		if (details.uriModuleId() != null) {
			return details.uriModuleId();
		}
		if (details.defaultModuleId() != null) {
			return details.defaultModuleId();
		}
		List<String> roots = details.modules() == null ? List.of() : details.modules().stream()
				.filter(module -> module.conceptId() != null && module.conceptId().equals(module.moduleId()))
				.map(SnowstormModule::conceptId)
				.filter(conceptId -> !conceptId.equals(MODEL_MODULE) && !conceptId.equals(INTERNATIONAL_MODULE))
				.distinct()
				.toList();
		return roots.size() == 1 ? roots.get(0) : null;
	}

	/** Releases of a code system, newest first. */
	public List<CodeSystemVersion> codeSystemVersions(String shortName) {
		// The short name goes in the request path, so only the shape Snowstorm uses is accepted.
		if (shortName == null || !shortName.matches("[A-Za-z0-9_-]{1,100}")) {
			throw new SnowstormException("Code system name is not valid: " + shortName, true, null);
		}
		SnowstormCodeSystemVersions response;
		try {
			response = restClient.get()
					.uri(uriBuilder -> uriBuilder.pathSegment("codesystems", shortName, "versions").build())
					.retrieve()
					.body(SnowstormCodeSystemVersions.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected
					? "Snowstorm has no code system called " + shortName + "."
					: "Snowstorm returned an error.", rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not list the releases of " + shortName + ".", false, e);
		}
		if (response == null || response.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return response.items().stream()
				.filter(item -> item.version() != null && isValidBranch(item.branchPath()))
				.map(item -> new CodeSystemVersion(item.version(), item.branchPath()))
				// Release names are dates written year first, so they sort as text.
				.sorted(Comparator.comparing(CodeSystemVersion::version).reversed())
				.toList();
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
				.map(SnowstormCodeSystem::toCodeSystem)
				.toList();
	}

	public ConceptDetails conceptDetails(String conceptId, String branch) {
		return browserConcept(conceptId, branch).toDetails();
	}

	/** Why a concept was made inactive and what the historical association reference sets point to instead. */
	public Inactivation inactivation(String conceptId, String branch) {
		BrowserConcept concept = browserConcept(conceptId, branch);
		return new Inactivation(concept.active(), concept.inactivationIndicator(),
				concept.associationTargets() == null ? Map.of() : concept.associationTargets());
	}

	private BrowserConcept browserConcept(String conceptId, String branch) {
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
		return concept;
	}

	/** The active concepts matching an ECL expression. */
	public ExpansionPage expand(String ecl, String branch, int offset, int limit) {
		return concepts("ecl", List.of(ecl), branch, offset, limit);
	}

	/** The active concepts whose descriptions match the words in a search term. */
	public ExpansionPage search(String term, String branch, int limit) {
		return concepts("term", List.of(term), branch, 0, limit);
	}

	/**
	 * Which of the given concepts are active on a branch, with their terms there. Unlike an ECL
	 * expression listing them, which Snowstorm refuses outright if any of them is inactive,
	 * inactive and unknown concepts are simply left out.
	 */
	public ExpansionPage activeConcepts(List<String> conceptIds, String branch) {
		return concepts("conceptIds", conceptIds, branch, 0, conceptIds.size());
	}

	private ExpansionPage concepts(String filterName, List<String> filterValues, String branch, int offset, int limit) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		SnowstormConceptPage page;
		try {
			page = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment(branch.split("/"))
							.pathSegment("concepts")
							// Placeholders, so the values are encoded rather than read as part of the URI.
							.queryParam(filterName, IntStream.range(0, filterValues.size())
									.mapToObj(index -> "{value" + index + "}").toArray())
							.queryParam("activeFilter", true)
							.queryParam("offset", offset)
							.queryParam("limit", limit)
							.build(filterValues.toArray()))
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
				.map(item -> new ExpansionPage.Concept(item.conceptId(), termOf(item.pt()), termOf(item.fsn()),
						item.pt() == null ? null : item.pt().lang()))
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

	record SnowstormTerm(String term, String lang) {
	}

	/**
	 * @param associationTargets keyed by association, for example REPLACED_BY or SAME_AS, each
	 *        with the concepts it points to. Only present on an inactive concept.
	 */
	record BrowserConcept(String conceptId, SnowstormTerm fsn, SnowstormTerm pt, boolean active,
			String definitionStatus, String moduleId, String effectiveTime, List<BrowserDescription> descriptions,
			List<BrowserRelationship> relationships, String inactivationIndicator,
			Map<String, List<String>> associationTargets) {

		private static final String IS_A = "116680003";

		ConceptDetails toDetails() {
			List<ConceptDetails.Description> activeDescriptions = orEmpty(descriptions).stream()
					.filter(BrowserDescription::active)
					.map(description -> new ConceptDetails.Description(description.term(), description.type(),
							description.lang(), description.preferredIn()))
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

		private static final java.util.Map<String, String> DIALECTS = java.util.Map.of(
				"900000000000509007", "en-US",
				"900000000000508004", "en-GB");

		/** The acceptability map is keyed by language reference set identifier. */
		List<String> preferredIn() {
			if (acceptabilityMap == null) {
				return List.of();
			}
			return acceptabilityMap.entrySet().stream()
					.filter(entry -> "PREFERRED".equals(entry.getValue()))
					.map(entry -> DIALECTS.getOrDefault(entry.getKey(), entry.getKey()))
					.sorted()
					.toList();
		}
	}

	record BrowserRelationship(boolean active, String typeId, String characteristicType, int groupId,
			SnowstormConcept type, SnowstormConcept target) {

		ConceptDetails.Relationship toRelationship() {
			return new ConceptDetails.Relationship(typeId, type == null ? null : termOf(type.pt()), target.conceptId(),
					termOf(target.pt()), groupId);
		}
	}

	record SnowstormCodeSystems(List<SnowstormCodeSystem> items) {
	}

	record SnowstormCodeSystem(String shortName, String name, String branchPath, SnowstormCodeSystemVersion latestVersion) {

		CodeSystem toCodeSystem() {
			// A release branch that fails the path check is treated as no release, so it is never used.
			boolean released = latestVersion != null && latestVersion.version() != null
					&& isValidBranch(latestVersion.branchPath());
			return new CodeSystem(shortName, name == null ? shortName : name, branchPath,
					released ? latestVersion.version() : null, released ? latestVersion.branchPath() : null);
		}
	}

	record SnowstormCodeSystemVersion(String version, String branchPath) {
	}

	record SnowstormCodeSystemVersions(List<SnowstormCodeSystemVersion> items) {
	}

	record SnowstormCodeSystemDetails(String uriModuleId, String defaultModuleId, List<SnowstormModule> modules) {
	}

	record SnowstormModule(String conceptId, String moduleId) {
	}
}
