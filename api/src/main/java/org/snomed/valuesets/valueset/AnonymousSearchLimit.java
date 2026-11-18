package org.snomed.valuesets.valueset;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps concept searches by people who have not signed in from loading the database: one per
 * client address every three minutes, and no more than twenty a minute from everyone together, in
 * case addresses are shared or made up. Held in memory, so it starts afresh when the application
 * restarts, and each instance of the application keeps its own count.
 */
@Component
public class AnonymousSearchLimit {

	static final Duration PER_ADDRESS = Duration.ofMinutes(3);
	static final Duration WINDOW = Duration.ofMinutes(1);
	static final int PER_WINDOW = 20;
	/** Past this many remembered addresses, those whose wait is over are forgotten. */
	private static final int TIDY_FROM = 10_000;

	private final Clock clock;
	private final Map<String, Instant> lastByAddress = new ConcurrentHashMap<>();
	private final Deque<Instant> recent = new ArrayDeque<>();

	@Autowired
	public AnonymousSearchLimit() {
		this(Clock.systemUTC());
	}

	AnonymousSearchLimit(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Counts a search from this address if it is allowed now.
	 *
	 * @return empty if the search may go ahead, otherwise how long to wait
	 */
	public synchronized Optional<Duration> tryAcquire(String address) {
		Instant now = clock.instant();
		Instant last = lastByAddress.get(address);
		if (last != null && last.plus(PER_ADDRESS).isAfter(now)) {
			return Optional.of(Duration.between(now, last.plus(PER_ADDRESS)));
		}
		while (!recent.isEmpty() && !recent.peekFirst().plus(WINDOW).isAfter(now)) {
			recent.pollFirst();
		}
		if (recent.size() >= PER_WINDOW) {
			return Optional.of(Duration.between(now, recent.peekFirst().plus(WINDOW)));
		}
		recent.addLast(now);
		lastByAddress.put(address, now);
		if (lastByAddress.size() > TIDY_FROM) {
			lastByAddress.values().removeIf(time -> !time.plus(PER_ADDRESS).isAfter(now));
		}
		return Optional.empty();
	}
}
