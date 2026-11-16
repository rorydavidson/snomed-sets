package org.snomed.valuesets.concept;

import java.util.List;

import org.snomed.valuesets.snowstorm.CodeSystem;
import org.snomed.valuesets.snowstorm.SnowstormClient;
import org.springframework.web.bind.annotation.GetMapping;
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
}
