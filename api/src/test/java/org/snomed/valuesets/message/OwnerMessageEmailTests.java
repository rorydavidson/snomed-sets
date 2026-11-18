package org.snomed.valuesets.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerMessageEmailTests {

	@Test
	void goesToTheOwnerWithTheSenderAsReplyToAndLinksBack() {
		OwnerMessageEmail email = OwnerMessageEmail.compose("owner@example.org", "Sam Sender", "sam@example.org",
				"Asthma diagnoses", "https://sets.example.org/value-sets/7", "https://sets.example.org/profile",
				"  Could you add occupational asthma?  ");

		assertThat(email.to()).isEqualTo("owner@example.org");
		assertThat(email.replyTo()).isEqualTo("sam@example.org");
		assertThat(email.subject()).isEqualTo("Message about your value set \"Asthma diagnoses\"");
		assertThat(email.text())
				.contains("Sam Sender sent you a message about your value set \"Asthma diagnoses\".")
				.contains("\nCould you add occupational asthma?\n")
				.contains("https://sets.example.org/value-sets/7")
				.contains("switch this off in your profile: https://sets.example.org/profile")
				.doesNotContain("owner@example.org");
	}

	@Test
	void keepsLineBreaksOutOfTheSubject() {
		OwnerMessageEmail email = OwnerMessageEmail.compose("o@example.org", "Sam", "s@example.org",
				"Name\r\nBcc: someone@example.org", "u", "p", "A message of some length");

		assertThat(email.subject()).doesNotContain("\n").doesNotContain("\r");
	}
}
