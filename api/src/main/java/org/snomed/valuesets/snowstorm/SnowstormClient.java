package org.snomed.valuesets.snowstorm;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.springframework.core.ParameterizedTypeReference;
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
		return fetchCodeSystems().stream().map(SnowstormCodeSystem::toCodeSystem).toList();
	}

	/**
	 * The edition a branch belongs to: the code system whose branch is the longest that contains it,
	 * so MAIN/SNOMEDCT-NO/2026-03-15 is the Norwegian Edition rather than the International one.
	 * Null if no code system contains the branch.
	 */
	public Edition editionFor(String branch) {
		return fetchCodeSystems().stream()
				.filter(item -> branch.equals(item.branchPath()) || branch.startsWith(item.branchPath() + "/"))
				.max(Comparator.comparingInt(item -> item.branchPath().length()))
				.map(SnowstormCodeSystem::toEdition)
				.orElse(null);
	}

	private List<SnowstormCodeSystem> fetchCodeSystems() {
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
				.toList();
	}

	public ConceptDetails conceptDetails(String conceptId, String branch) {
		return browserConcept(conceptId, branch, US_ENGLISH).toDetails();
	}

	/**
	 * The same, with terms in the given language where the concept has them, for example "no".
	 * English is asked for after it, so a concept without a translation still has terms.
	 */
	public ConceptDetails conceptDetails(String conceptId, String branch, String language) {
		return browserConcept(conceptId, branch, acceptLanguage(language)).toDetails();
	}

	private static String acceptLanguage(String language) {
		return language == null || language.equals("en") ? US_ENGLISH : language + "," + US_ENGLISH;
	}

	/** Why a concept was made inactive and what the historical association reference sets point to instead. */
	public Inactivation inactivation(String conceptId, String branch) {
		BrowserConcept concept = browserConcept(conceptId, branch, US_ENGLISH);
		return new Inactivation(concept.active(), termOf(concept.pt()), concept.inactivationIndicator(),
				concept.associationTargets() == null ? Map.of() : concept.associationTargets());
	}

	/**
	 * The concept a description belongs to, for when someone has copied a description
	 * identifier instead of a concept identifier. Empty when the branch has no such description.
	 */
	public Optional<String> conceptIdOfDescription(String descriptionId, String branch) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		if (!descriptionId.matches("\\d{6,18}")) {
			throw new SnowstormException("Description identifier is not valid: " + descriptionId, true, null);
		}
		SnowstormDescription description;
		try {
			description = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment(branch.split("/"))
							.pathSegment("descriptions", descriptionId)
							.build())
					.retrieve()
					.body(SnowstormDescription.class);
		}
		catch (RestClientResponseException e) {
			if (e.getStatusCode().is4xxClientError()) {
				return Optional.empty();
			}
			throw new SnowstormException("Snowstorm returned an error.", false, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		return Optional.ofNullable(description).map(SnowstormDescription::conceptId);
	}

	record SnowstormDescription(String descriptionId, String conceptId) {
	}

	private BrowserConcept browserConcept(String conceptId, String branch, String acceptLanguage) {
		// The language goes in a request header, so only a language-tag list is accepted.
		if (!acceptLanguage.matches("[A-Za-z0-9,;=.-]{1,100}")) {
			throw new SnowstormException("Language is not valid: " + acceptLanguage, true, null);
		}
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
					.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
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
		return concepts("ecl", List.of(ecl), branch, offset, limit, US_ENGLISH);
	}

	/** The same, with preferred terms in the given language where the concepts have them. */
	public ExpansionPage expand(String ecl, String branch, int offset, int limit, String language) {
		return concepts("ecl", List.of(ecl), branch, offset, limit, acceptLanguage(language));
	}

	/** The active concepts whose descriptions match the words in a search term. */
	public ExpansionPage search(String term, String branch, int limit) {
		return concepts("term", List.of(term), branch, 0, limit, US_ENGLISH);
	}

	/**
	 * Concepts with an active description, in any of the given languages, containing words that
	 * start with every word of the term, in any order. This is Snowstorm's own term search, so
	 * "heart att" finds "Heart attack", a synonym of Myocardial infarction. Inactive concepts are
	 * included, as stored concept lists may hold them.
	 *
	 * @param languages language codes such as "en" and "fr"; every description in them is searched
	 * @return the first matches, with the total so a caller can tell when there were more
	 */
	public ExpansionPage conceptsWithDescriptionMatching(String term, String branch, Collection<String> languages,
			int limit) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		// Snowstorm searches the descriptions in the languages of the Accept-Language header.
		String acceptLanguage = String.join(",", languages);
		if (!acceptLanguage.matches("[A-Za-z0-9,-]{1,100}")) {
			throw new SnowstormException("Language is not valid: " + acceptLanguage, true, null);
		}
		Map<String, Object> body = new HashMap<>();
		body.put("termFilter", term);
		body.put("termActive", true);
		body.put("offset", 0);
		body.put("limit", limit);
		SnowstormConceptPage page;
		try {
			page = restClient.post()
					.uri(uriBuilder -> uriBuilder.pathSegment(branch.split("/")).pathSegment("concepts", "search").build())
					.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(SnowstormConceptPage.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected ? "Snowstorm rejected the search." : "Snowstorm returned an error.",
					rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		if (page == null || page.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return new ExpansionPage(page.items().stream()
				.map(item -> new ExpansionPage.Concept(item.conceptId(), termOf(item.pt()), termOf(item.fsn()),
						item.pt() == null ? null : item.pt().lang()))
				.toList(), page.total(), 0, limit);
	}

	/**
	 * Active descriptions of active concepts matching a term, each with its concept. Snowstorm
	 * puts the shortest descriptions first, so one that is exactly the term comes before longer
	 * ones that merely contain it.
	 *
	 * @param languages language codes such as "en" and "fr"; only descriptions in them are searched
	 */
	public List<DescriptionMatch> descriptionsMatching(String term, String branch, Collection<String> languages,
			int limit) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		String acceptLanguage = String.join(",", languages);
		if (!acceptLanguage.matches("[A-Za-z0-9,-]{1,100}")) {
			throw new SnowstormException("Language is not valid: " + acceptLanguage, true, null);
		}
		SnowstormDescriptionPage page;
		try {
			page = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment("browser")
							.pathSegment(branch.split("/"))
							.pathSegment("descriptions")
							// A placeholder, so the term is encoded rather than read as part of the URI.
							.queryParam("term", "{term}")
							.queryParam("active", true)
							.queryParam("conceptActive", true)
							.queryParam("language", languages.toArray())
							.queryParam("offset", 0)
							.queryParam("limit", limit)
							.build(term))
					.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
					.retrieve()
					.body(SnowstormDescriptionPage.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected ? "Snowstorm rejected the search." : "Snowstorm returned an error.",
					rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		if (page == null || page.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return page.items().stream()
				.filter(item -> item.concept() != null && item.term() != null)
				.map(item -> new DescriptionMatch(item.term(), item.languageCode(),
						new ExpansionPage.Concept(item.concept().conceptId(), termOf(item.concept().pt()),
								termOf(item.concept().fsn()),
								item.concept().pt() == null ? null : item.concept().pt().lang())))
				.toList();
	}

	record SnowstormDescriptionPage(List<SnowstormDescriptionMatch> items) {
	}

	record SnowstormDescriptionMatch(String term, String languageCode, SnowstormConcept concept) {
	}

	/**
	 * The same as {@link #expand(String, String, int, int)}, with the expression sent in the request
	 * body. Used for expressions listing many concepts, which would be too long for a URL.
	 */
	public ExpansionPage expandLong(String ecl, String branch, int offset, int limit) {
		return expandLong(ecl, branch, offset, limit, null);
	}

	/**
	 * The same, keeping only the given concepts. Much faster than writing them into the expression
	 * as an OR list, which Snowstorm takes seconds to read once it runs to thousands of concepts.
	 */
	public ExpansionPage expandLong(String ecl, String branch, int offset, int limit, Collection<String> onlyConceptIds) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		SnowstormConceptPage page;
		try {
			page = restClient.post()
					.uri(uriBuilder -> uriBuilder.pathSegment(branch.split("/")).pathSegment("concepts", "search").build())
					.header(HttpHeaders.ACCEPT_LANGUAGE, US_ENGLISH)
					.contentType(MediaType.APPLICATION_JSON)
					.body(searchBody(ecl, offset, limit, onlyConceptIds))
					.retrieve()
					.body(SnowstormConceptPage.class);
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected
					? "Snowstorm rejected the request. Check the ECL expression and branch."
					: "Snowstorm returned an error.", rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		if (page == null || page.items() == null) {
			throw new SnowstormException("Snowstorm returned an empty response.", false, null);
		}
		return new ExpansionPage(page.items().stream()
				.map(item -> new ExpansionPage.Concept(item.conceptId(), termOf(item.pt()), termOf(item.fsn()),
						item.pt() == null ? null : item.pt().lang()))
				.toList(), page.total(), offset, limit);
	}

	private static Map<String, Object> searchBody(String ecl, int offset, int limit, Collection<String> onlyConceptIds) {
		Map<String, Object> body = new HashMap<>();
		body.put("eclFilter", ecl);
		body.put("activeFilter", true);
		body.put("offset", offset);
		body.put("limit", limit);
		if (onlyConceptIds != null) {
			body.put("conceptIds", onlyConceptIds);
		}
		return body;
	}

	/**
	 * The inferred parents of each of the given concepts, loaded in one call rather than one per
	 * concept. Concepts the branch does not have are left out.
	 */
	public Map<String, List<String>> inferredParents(Collection<String> conceptIds, String branch) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		List<BrowserConcept> concepts;
		try {
			concepts = restClient.post()
					.uri(uriBuilder -> uriBuilder.pathSegment("browser").pathSegment(branch.split("/"))
							.pathSegment("concepts", "bulk-load").build())
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("conceptIds", conceptIds))
					.retrieve()
					.body(new ParameterizedTypeReference<List<BrowserConcept>>() {
					});
		}
		catch (RestClientResponseException e) {
			boolean rejected = e.getStatusCode().is4xxClientError();
			throw new SnowstormException(rejected ? "Snowstorm rejected the request." : "Snowstorm returned an error.",
					rejected, e);
		}
		catch (RestClientException e) {
			throw new SnowstormException("Snowstorm could not be reached or did not return JSON.", false, e);
		}
		Map<String, List<String>> parents = new HashMap<>();
		if (concepts != null) {
			concepts.forEach(concept -> parents.put(concept.conceptId(), concept.relationships() == null ? List.of()
					: concept.relationships().stream()
							.filter(relationship -> relationship.active() && "116680003".equals(relationship.typeId())
									&& "INFERRED_RELATIONSHIP".equals(relationship.characteristicType()))
							.map(relationship -> relationship.target().conceptId())
							.distinct()
							.toList()));
		}
		return parents;
	}

	/**
	 * Which of the given concepts are active on a branch, with their terms there. Unlike an ECL
	 * expression listing them, which Snowstorm refuses outright if any of them is inactive,
	 * inactive and unknown concepts are simply left out.
	 */
	public ExpansionPage activeConcepts(List<String> conceptIds, String branch) {
		return concepts("conceptIds", conceptIds, branch, 0, conceptIds.size(), US_ENGLISH);
	}

	/** The same, with preferred terms in the given language where the concepts have them, for example "no". */
	public ExpansionPage activeConcepts(List<String> conceptIds, String branch, String language) {
		return concepts("conceptIds", conceptIds, branch, 0, conceptIds.size(), acceptLanguage(language));
	}

	private ExpansionPage concepts(String filterName, List<String> filterValues, String branch, int offset, int limit,
			String acceptLanguage) {
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
					.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage)
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
							description.lang(), description.preferredIn(), description.acceptableIn(),
							description.moduleId()))
					.toList();
			List<BrowserRelationship> inferred = orEmpty(relationships).stream()
					.filter(relationship -> relationship.active()
							&& "INFERRED_RELATIONSHIP".equals(relationship.characteristicType())
							&& relationship.target() != null)
					.toList();
			return new ConceptDetails(conceptId, termOf(fsn), termOf(pt), pt == null ? null : pt.lang(), active,
					definitionStatus, moduleId, effectiveTime, activeDescriptions,
					inferred.stream().filter(r -> IS_A.equals(r.typeId())).map(BrowserRelationship::toRelationship).toList(),
					inferred.stream().filter(r -> !IS_A.equals(r.typeId())).map(BrowserRelationship::toRelationship).toList(),
					null, null, false, Map.of(), List.of());
		}

		private static <T> List<T> orEmpty(List<T> list) {
			return list == null ? List.of() : list;
		}
	}

	record BrowserDescription(String term, String type, String lang, boolean active, String moduleId,
			java.util.Map<String, String> acceptabilityMap) {

		private static final java.util.Map<String, String> DIALECTS = java.util.Map.of(
				"900000000000509007", "en-US",
				"900000000000508004", "en-GB");

		/**
		 * The language reference sets in which it is preferred. US and GB English are named by
		 * dialect; others are given by identifier.
		 */
		List<String> preferredIn() {
			return refsetsWhere("PREFERRED");
		}

		List<String> acceptableIn() {
			return refsetsWhere("ACCEPTABLE");
		}

		/** The acceptability map is keyed by language reference set identifier. */
		private List<String> refsetsWhere(String acceptability) {
			if (acceptabilityMap == null) {
				return List.of();
			}
			return acceptabilityMap.entrySet().stream()
					.filter(entry -> acceptability.equals(entry.getValue()))
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

	record SnowstormCodeSystem(String shortName, String name, String branchPath, SnowstormCodeSystemVersion latestVersion,
			Map<String, String> languages, List<SnowstormModule> modules) {

		Edition toEdition() {
			Map<String, String> known = languages == null || languages.isEmpty() ? Map.of("en", "English") : languages;
			// The edition's own language comes first; English only when it has no other.
			String language = known.keySet().stream().filter(code -> !code.equals("en")).sorted().findFirst().orElse("en");
			Map<String, String> owners = new java.util.HashMap<>();
			if (modules != null) {
				modules.stream()
						.filter(module -> module.conceptId() != null && module.moduleId() != null)
						.forEach(module -> owners.put(module.conceptId(), module.moduleId()));
			}
			return new Edition(shortName, name == null ? shortName : name, language, known, owners);
		}

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
