package org.snomed.valuesets.valueset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Access rules live in {@link EclSuggestionService}; this class only maps HTTP to it. */
@RestController
public class EclSuggestionController {

	private final EclSuggestionService service;

	public EclSuggestionController(EclSuggestionService service) {
		this.service = service;
	}

	/** An ECL expression matching a hand-picked list. Owner only, because it is expensive. Nothing is saved. */
	@GetMapping("/api/value-sets/{id}/ecl-suggestion")
	public EclSuggestion suggest(@PathVariable long id, CurrentUser user) {
		return service.suggest(id, user);
	}

	/** The expression kept with the set, or 204 if there is none the caller may see. */
	@GetMapping("/api/value-sets/{id}/saved-ecl")
	public ResponseEntity<SavedEclView> saved(@PathVariable long id, CurrentUser user) {
		return service.saved(id, user).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping("/api/value-sets/{id}/saved-ecl")
	public SavedEclView save(@PathVariable long id, @Valid @RequestBody SaveRequest request, CurrentUser user) {
		return service.save(id, request.ecl(), user);
	}

	@DeleteMapping("/api/value-sets/{id}/saved-ecl")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable long id, CurrentUser user) {
		service.remove(id, user);
	}

	public record SaveRequest(@NotBlank @Size(max = ValueSetDefinition.MAX_ECL_LENGTH) String ecl) {
	}
}
