package org.snomed.valuesets.valueset;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Access rules live in {@link EclSuggestionService}; this class only maps HTTP to it. */
@RestController
public class EclSuggestionController {

	private final EclSuggestionService service;

	public EclSuggestionController(EclSuggestionService service) {
		this.service = service;
	}

	/** An ECL expression matching a hand-picked list, for anyone who can see it. Nothing is saved. */
	@GetMapping("/api/value-sets/{id}/ecl-suggestion")
	public EclSuggestion suggest(@PathVariable long id, CurrentUser user) {
		return service.suggest(id, user);
	}
}
