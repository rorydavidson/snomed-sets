package org.snomed.valuesets.valueset;

import java.time.Duration;
import java.util.List;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** Finds the value sets containing concepts that match a search. Open to visitors, who are limited. */
@RestController
public class ConceptSearchController {

	private final ConceptSearchService service;
	private final AnonymousSearchLimit anonymousLimit;

	public ConceptSearchController(ConceptSearchService service, AnonymousSearchLimit anonymousLimit) {
		this.service = service;
		this.anonymousLimit = anonymousLimit;
	}

	@GetMapping("/api/concept-search")
	public ResponseEntity<?> search(@RequestParam(defaultValue = "") String term, CurrentUser user,
			HttpServletRequest request) {
		// Checked first, so a search too short to run does not use up a visitor's turn.
		String query = ConceptSearchService.checked(term);
		if (!user.isSignedIn()) {
			var wait = anonymousLimit.tryAcquire(request.getRemoteAddr());
			if (wait.isPresent()) {
				return tooSoon(wait.get());
			}
		}
		List<ConceptSearchHit> hits = service.search(query, user);
		return ResponseEntity.ok(hits);
	}

	private static ResponseEntity<ProblemDetail> tooSoon(Duration wait) {
		long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				"Without signing in you can search for concepts once every 3 minutes. Try again in "
						+ seconds + " seconds, or sign in to search freely.");
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.body(problem);
	}
}
