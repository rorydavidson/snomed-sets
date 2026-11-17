package org.snomed.valuesets.concept;

import java.util.List;

import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.CodeSystemVersion;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CodeSystemController {

	private final SnowstormClient snowstorm;

	public CodeSystemController(SnowstormClient snowstorm) {
		this.snowstorm = snowstorm;
	}

	/** The code systems a new value set can be built against. */
	@GetMapping("/api/code-systems")
	public List<CodeSystem> codeSystems() {
		return snowstorm.codeSystems();
	}

	/** The releases a new value set can be built on, newest first. */
	@GetMapping("/api/code-systems/{shortName}/versions")
	public List<CodeSystemVersion> versions(@PathVariable String shortName) {
		return snowstorm.codeSystemVersions(shortName);
	}
}
