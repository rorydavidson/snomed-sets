package org.snomed.valuesets.profile;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** What a person chooses to add about themselves: an organisation, a picture and whether they can be messaged. */
@Entity
@Table(name = "user_profile")
public class UserProfile {

	/** The account's subject. Never sent to a browser. */
	@Id
	private String subject;

	/** Identifies the profile in URLs, such as the picture's, in place of the subject. */
	@Column(name = "public_id", nullable = false, length = 36)
	private String publicId;

	@Column(length = 120)
	private String organisation;

	/** Set only while the person lets signed-in people message them. */
	@Column(name = "contact_email", length = 320)
	private String contactEmail;

	/**
	 * Loaded with the profile, so lists of value sets use {@link OwnerProfile} instead, which
	 * leaves it out.
	 */
	@Column(columnDefinition = "MEDIUMBLOB")
	private byte[] avatar;

	@Column(name = "avatar_updated_at")
	private Instant avatarUpdatedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserProfile() {
		// for JPA
	}

	UserProfile(String subject) {
		this.subject = subject;
		this.publicId = UUID.randomUUID().toString();
		this.updatedAt = Instant.now();
	}

	void update(String organisation, String contactEmail) {
		this.organisation = organisation;
		this.contactEmail = contactEmail;
		this.updatedAt = Instant.now();
	}

	void setAvatar(byte[] png) {
		this.avatar = png;
		this.avatarUpdatedAt = png == null ? null : Instant.now();
		this.updatedAt = Instant.now();
	}

	public String getSubject() {
		return subject;
	}

	public String getPublicId() {
		return publicId;
	}

	public String getOrganisation() {
		return organisation;
	}

	public String getContactEmail() {
		return contactEmail;
	}

	byte[] getAvatar() {
		return avatar;
	}

	public Instant getAvatarUpdatedAt() {
		return avatarUpdatedAt;
	}

	public boolean hasAvatar() {
		return avatarUpdatedAt != null;
	}

	public String avatarUrl() {
		return OwnerProfile.avatarUrl(publicId, avatarUpdatedAt);
	}
}
