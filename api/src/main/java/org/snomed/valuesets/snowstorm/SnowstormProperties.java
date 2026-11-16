package org.snomed.valuesets.snowstorm;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where to find Snowstorm. Point baseUrl at the public server or at a local
 * instance; nothing else in the application needs to change.
 */
@Validated
@ConfigurationProperties(prefix = "snowstorm")
public record SnowstormProperties(@NotBlank String baseUrl, @NotBlank String userAgent) {
}
