package org.snomed.valuesets.message;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.snomed.valuesets.profile.ProfileService;
import org.snomed.valuesets.user.CurrentUser;
import org.snomed.valuesets.valueset.ValueSet;
import org.snomed.valuesets.valueset.ValueSetService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets a signed-in person message the owner of a value set they can see, without either learning
 * the owner's address. The owner can reply, which is when the sender's address is shown to them.
 */
@Service
public class OwnerMessageService {

	static final int MIN_LENGTH = 10;
	static final int MAX_LENGTH = 2000;
	static final int PER_HOUR = 5;
	static final int PER_DAY = 20;

	private final ValueSetService valueSets;
	private final ProfileService profiles;
	private final MessagingProperties properties;
	private final ResendClient resend;
	private final JdbcTemplate jdbc;

	OwnerMessageService(ValueSetService valueSets, ProfileService profiles, MessagingProperties properties,
			ResendClient resend, JdbcTemplate jdbc) {
		this.valueSets = valueSets;
		this.profiles = profiles;
		this.properties = properties;
		this.resend = resend;
		this.jdbc = jdbc;
	}

	public void send(long valueSetId, String message, CurrentUser sender) {
		if (!properties.enabled()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Messaging is not set up.");
		}
		ValueSet valueSet = valueSets.visible(valueSetId, sender);
		if (valueSet.isOwnedBy(sender.subject())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is your own value set.");
		}
		// Reported the same way as a set that does not exist, so nobody can test who allows messages.
		String ownerEmail = profiles.contactEmail(valueSet.getOwnerSubject())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This owner cannot be messaged."));
		if (sender.email() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Your account has no verified email address, so the owner could not reply to you.");
		}

		String appUrl = properties.appUrlWithoutSlash();
		long logged = recordSending(sender, valueSetId);
		try {
			resend.send(OwnerMessageEmail.compose(ownerEmail, sender.name(), sender.email(), valueSet.getName(),
					appUrl + "/value-sets/" + valueSetId, appUrl + "/profile", message));
		} catch (RuntimeException e) {
			// A message that was not sent does not count towards the limit.
			jdbc.update("DELETE FROM owner_message WHERE id = ?", logged);
			throw e;
		}
	}

	/** Checks the sender is within the limits, and notes who sent to which set and when. Never the message. */
	private long recordSending(CurrentUser sender, long valueSetId) {
		Instant now = Instant.now();
		jdbc.update("DELETE FROM owner_message WHERE sent_at < ?", Timestamp.from(now.minus(Duration.ofDays(1))));
		if (sentSince(sender, now.minus(Duration.ofHours(1))) >= PER_HOUR
				|| sentSince(sender, now.minus(Duration.ofDays(1))) >= PER_DAY) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"You have sent the most messages allowed for now. Try again later.");
		}
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			var insert = connection.prepareStatement(
					"INSERT INTO owner_message (sender_subject, value_set_id, sent_at) VALUES (?, ?, ?)",
					new String[] { "id" });
			insert.setString(1, sender.subject());
			insert.setLong(2, valueSetId);
			insert.setTimestamp(3, Timestamp.from(now));
			return insert;
		}, key);
		return key.getKey().longValue();
	}

	private int sentSince(CurrentUser sender, Instant since) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM owner_message WHERE sender_subject = ? AND sent_at >= ?", Integer.class,
				sender.subject(), Timestamp.from(since));
		return count == null ? 0 : count;
	}
}
