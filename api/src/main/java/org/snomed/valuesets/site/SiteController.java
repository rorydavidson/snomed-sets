package org.snomed.valuesets.site;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facts about this deployment, for pages anyone can read such as the privacy page. */
@RestController
public class SiteController {

	private final SiteProperties properties;
	private final FeatureProperties features;

	public SiteController(SiteProperties properties, FeatureProperties features) {
		this.properties = properties;
		this.features = features;
	}

	@GetMapping("/api/site")
	public SiteInfo site() {
		return new SiteInfo(blankToNull(properties.operator()), blankToNull(properties.privacyContact()),
				features.mapsEnabled());
	}

	/**
	 * Null fields have not been set for this deployment.
	 *
	 * @param mapsEnabled so the frontend can hide maps when this deployment has switched them off
	 */
	public record SiteInfo(String operator, String privacyContact, boolean mapsEnabled) {
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
