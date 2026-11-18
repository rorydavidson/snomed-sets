package org.snomed.valuesets.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessagingPropertiesTests {

	@Test
	void isOnOnlyWhenTheKeySenderAndAppAddressAreAllSet() {
		assertThat(new MessagingProperties("key", "from@example.org", null, "https://sets.example.org").enabled()).isTrue();
		assertThat(new MessagingProperties("", "from@example.org", null, "https://sets.example.org").enabled()).isFalse();
		assertThat(new MessagingProperties("key", " ", null, "https://sets.example.org").enabled()).isFalse();
		assertThat(new MessagingProperties("key", "from@example.org", null, null).enabled()).isFalse();
	}

	@Test
	void dropsATrailingSlashFromTheAppAddress() {
		assertThat(new MessagingProperties("k", "f", null, "https://sets.example.org/").appUrlWithoutSlash())
				.isEqualTo("https://sets.example.org");
	}
}
