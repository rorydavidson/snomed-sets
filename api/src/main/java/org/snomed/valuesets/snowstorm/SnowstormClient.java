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

	public ExpansionPage expand(String ecl, String branch, int offset, int limit) {
		if (!isValidBranch(branch)) {
			throw new SnowstormException("Branch path is not valid: " + branch, true, null);
		}
		SnowstormConceptPage page;
		try {
			page = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment(branch.split("/"))
							.pathSegment("concepts")
							.queryParam("ecl", "{ecl}")
							.queryParam("activeFilter", true)
							.queryParam("offset", offset)
							.queryParam("limit", limit)
							.build(ecl))
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
}
