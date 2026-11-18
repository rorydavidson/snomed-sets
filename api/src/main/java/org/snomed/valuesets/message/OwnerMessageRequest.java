package org.snomed.valuesets.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What a signed-in person writes to a value set's owner. Plain text. */
public record OwnerMessageRequest(@NotBlank @Size(min = OwnerMessageService.MIN_LENGTH,
		max = OwnerMessageService.MAX_LENGTH) String message) {
}
