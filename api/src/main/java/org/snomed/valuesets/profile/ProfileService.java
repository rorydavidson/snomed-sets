package org.snomed.valuesets.profile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.snomed.valuesets.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

	private final UserProfileRepository profiles;

	public ProfileService(UserProfileRepository profiles) {
		this.profiles = profiles;
	}

	@Transactional(readOnly = true)
	public ProfileView get(CurrentUser user) {
		return ProfileView.of(profiles.findById(user.subject()).orElse(null), user);
	}

	/**
	 * Saves the organisation and whether the person can be messaged. Their account's verified
	 * address is stored only while messaging is on, and removed as soon as it is turned off.
	 */
	@Transactional
	public ProfileView update(ProfileUpdate update, CurrentUser user) {
		if (update.contactable() && user.email() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Your account has no verified email address, so messages could not reach you. If you have "
							+ "just added one, sign out and in again.");
		}
		UserProfile profile = findOrCreate(user);
		profile.update(blankToNull(update.organisation()), update.contactable() ? user.email() : null);
		return ProfileView.of(profiles.save(profile), user);
	}

	@Transactional
	public ProfileView setAvatar(byte[] upload, CurrentUser user) {
		UserProfile profile = findOrCreate(user);
		profile.setAvatar(AvatarImages.normalise(upload));
		return ProfileView.of(profiles.save(profile), user);
	}

	@Transactional
	public ProfileView removeAvatar(CurrentUser user) {
		UserProfile profile = findOrCreate(user);
		profile.setAvatar(null);
		return ProfileView.of(profiles.save(profile), user);
	}

	@Transactional(readOnly = true)
	public Optional<byte[]> avatar(String publicId) {
		return profiles.findAvatarByPublicId(publicId);
	}

	/** Profiles of the given owners, by subject, with an empty one for anyone who has none. */
	@Transactional(readOnly = true)
	public Map<String, OwnerProfile> ownerProfiles(Collection<String> subjects) {
		if (subjects.isEmpty()) {
			return Map.of();
		}
		Map<String, OwnerProfile> found = profiles.findOwnerProfiles(subjects.stream().distinct().toList()).stream()
				.collect(Collectors.toMap(OwnerProfile::subject, Function.identity()));
		return subjects.stream().distinct()
				.collect(Collectors.toMap(Function.identity(), subject -> found.getOrDefault(subject, OwnerProfile.none(subject))));
	}

	public OwnerProfile ownerProfile(String subject) {
		return ownerProfiles(List.of(subject)).get(subject);
	}

	/** The address messages to this owner go to, or empty if they cannot be messaged. */
	@Transactional(readOnly = true)
	public Optional<String> contactEmail(String subject) {
		return profiles.findById(subject).map(UserProfile::getContactEmail);
	}

	private UserProfile findOrCreate(CurrentUser user) {
		return profiles.findById(user.subject()).orElseGet(() -> new UserProfile(user.subject()));
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim().replaceAll("\\s+", " ");
	}
}
