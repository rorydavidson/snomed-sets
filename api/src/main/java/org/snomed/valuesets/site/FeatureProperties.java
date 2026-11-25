package org.snomed.valuesets.site;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parts of the application a deployment can switch off.
 *
 * @param maps whether people can make maps. When off, the maps API does not exist and the
 *        frontend hides every way to reach it. Maps already made are kept, and come back when it is
 *        switched on again.
 */
@ConfigurationProperties("features")
public record FeatureProperties(@DefaultValue Maps maps) {

	public record Maps(@DefaultValue("true") boolean enabled) {
	}

	public boolean mapsEnabled() {
		return maps.enabled();
	}
}
