package org.snomed.valuesets.valueset;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checks a list of identifiers before they go into a hand-picked list. It reads only public
 * terminology and saves nothing, so any signed-in user may call it.
 */
@RestController
@RequestMapping("/api/identifier-checks")
public class IdentifierImportController {

	private final IdentifierImportService service;

	public IdentifierImportController(IdentifierImportService service) {
		this.service = service;
	}

	@PostMapping
	public IdentifierCheck check(@Valid @RequestBody IdentifierCheck.Request request) {
		return service.check(request);
	}

	/** The concepts an ECL expression matches now, in the same shape, to add to a list once. */
	@PostMapping("/ecl")
	public IdentifierCheck checkEcl(@Valid @RequestBody IdentifierCheck.EclRequest request) {
		return service.checkEcl(request);
	}
}
