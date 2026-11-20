package org.snomed.valuesets.map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The parts of a map its owner can change at any time.
 *
 * @param localSystemName what the owner calls the local list of codes
 * @param localSystemUri an identifier for the local code system, or null. It is stored as given
 *        and never fetched.
 * @param localSystemVersion the version of the local list the map was made for, or null
 */
public record MapDetails(@NotBlank @Size(max = 200) String name,
		@NotNull @Size(max = 2000) String description,
		@NotBlank @Size(max = 200) String localSystemName,
		@Size(max = 500) String localSystemUri,
		@Size(max = 100) String localSystemVersion) {

	public MapDetails {
		name = name == null ? null : name.strip();
		description = description == null ? "" : description.strip();
		localSystemName = localSystemName == null ? null : localSystemName.strip();
		localSystemUri = blankToNull(localSystemUri);
		localSystemVersion = blankToNull(localSystemVersion);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
