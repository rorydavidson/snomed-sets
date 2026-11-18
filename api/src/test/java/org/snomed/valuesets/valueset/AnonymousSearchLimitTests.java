package org.snomed.valuesets.valueset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class AnonymousSearchLimitTests {

	private Instant now = Instant.parse("2026-11-18T10:00:00Z");
	private final AnonymousSearchLimit limit = new AnonymousSearchLimit(new Clock() {
		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	});

	@Test
	void allowsOneSearchPerAddressEveryThreeMinutes() {
		assertThat(limit.tryAcquire("203.0.113.1")).isEmpty();

		now = now.plusSeconds(60);
		assertThat(limit.tryAcquire("203.0.113.1")).contains(Duration.ofMinutes(2));
		assertThat(limit.tryAcquire("203.0.113.2")).isEmpty();

		now = now.plusSeconds(120);
		assertThat(limit.tryAcquire("203.0.113.1")).isEmpty();
	}

	@Test
	void capsEveryoneTogetherWhenManyAddressesSearchAtOnce() {
		for (int i = 0; i < AnonymousSearchLimit.PER_WINDOW; i++) {
			assertThat(limit.tryAcquire("198.51.100." + i)).isEmpty();
		}
		assertThat(limit.tryAcquire("198.51.100.200")).contains(Duration.ofMinutes(1));

		now = now.plus(AnonymousSearchLimit.WINDOW);
		assertThat(limit.tryAcquire("198.51.100.200")).isEmpty();
	}

	@Test
	void aRefusedSearchDoesNotStartTheWaitAgain() {
		limit.tryAcquire("203.0.113.1");
		now = now.plusSeconds(170);
		limit.tryAcquire("203.0.113.1");

		now = now.plusSeconds(10);
		assertThat(limit.tryAcquire("203.0.113.1")).isEmpty();
	}
}
