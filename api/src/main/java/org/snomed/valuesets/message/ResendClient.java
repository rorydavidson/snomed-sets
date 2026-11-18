package org.snomed.valuesets.message;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Sends email through Resend's REST API. See https://resend.com/docs/api-reference/emails/send-email. */
@Component
public class ResendClient {

	private static final Logger log = LoggerFactory.getLogger(ResendClient.class);

	private final MessagingProperties properties;
	private final RestClient http;

	ResendClient(MessagingProperties properties, RestClient.Builder builder) {
		this.properties = properties;
		this.http = builder.baseUrl(properties.resendBaseUrl() == null ? "https://api.resend.com" : properties.resendBaseUrl())
				.build();
	}

	public void send(OwnerMessageEmail email) {
		try {
			http.post()
					.uri("/emails")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.resendApiKey())
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of(
							"from", properties.from(),
							"to", List.of(email.to()),
							"reply_to", email.replyTo(),
							"subject", email.subject(),
							"text", email.text()))
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException e) {
			// Only the status is logged: never the addresses, the message or Resend's reply, which may repeat them.
			log.warn("Resend refused a message with status {}", e.getStatusCode().value());
			throw unavailable();
		} catch (RestClientException e) {
			log.warn("Resend could not be reached: {}", e.getClass().getSimpleName());
			throw unavailable();
		}
	}

	private static ResponseStatusException unavailable() {
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The message could not be sent just now. Try again later.");
	}
}
