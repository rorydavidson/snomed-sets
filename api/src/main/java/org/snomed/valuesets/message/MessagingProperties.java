package org.snomed.valuesets.message;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How messages to value set owners are sent. Messaging is off unless the Resend API key, the
 * sender address and the application's address are all set.
 *
 * @param from a sender on a domain verified in Resend, for example
 *        "Sets &amp; Maps &lt;no-reply@example.org&gt;"
 * @param appUrl the public address of this application, for the links in emails, for example
 *        https://valuesets.example.org. Configured rather than taken from the request, so a sender
 *        cannot change where the links in someone else's email point.
 */
@ConfigurationProperties("messages")
public record MessagingProperties(String resendApiKey, String from, String resendBaseUrl, String appUrl) {

	public boolean enabled() {
		return isSet(resendApiKey) && isSet(from) && isSet(appUrl);
	}

	/** The application's address without a trailing slash. */
	public String appUrlWithoutSlash() {
		return appUrl.replaceAll("/+$", "");
	}

	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}
}
