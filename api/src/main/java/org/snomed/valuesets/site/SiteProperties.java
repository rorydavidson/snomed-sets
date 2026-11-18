package org.snomed.valuesets.site;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What each deployment says about itself on the privacy page.
 *
 * @param operator who runs this site, for example an organisation's name; empty when not set
 * @param privacyContact an email address for privacy questions; empty when not set
 */
@ConfigurationProperties("site")
public record SiteProperties(String operator, String privacyContact) {
}
