package org.snomed.valuesets.message;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** Messages to value set owners. Signing in is required, as for every write. */
@RestController
public class OwnerMessageController {

	private final OwnerMessageService service;

	public OwnerMessageController(OwnerMessageService service) {
		this.service = service;
	}

	@PostMapping("/api/value-sets/{id}/messages")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void send(@PathVariable long id, @Valid @RequestBody OwnerMessageRequest request, CurrentUser user) {
		service.send(id, request.message(), user);
	}
}
