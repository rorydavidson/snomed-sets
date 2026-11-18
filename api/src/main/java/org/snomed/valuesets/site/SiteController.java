package org.snomed.valuesets.site;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facts about this deployment, for pages anyone can read such as the privacy page. */
@RestController
public class SiteController {

	private final SiteProperties properties;

	public SiteController(SiteProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/api/site")
	public SiteInfo site() {
		return new SiteInfo(blankToNull(properties.operator()), blankToNull(properties.privacyContact()));
	}

	/** Null fields have not been set for this deployment. */
	public record SiteInfo(String operator, String privacyContact) {
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
