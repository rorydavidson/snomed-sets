package org.snomed.valuesets.profile;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface UserProfileRepository extends Repository<UserProfile, String> {

	Optional<UserProfile> findById(String subject);

	/** What value sets show about their owners, without loading anyone's picture. */
	@Query("""
			select new org.snomed.valuesets.profile.OwnerProfile(
				p.subject, p.publicId, p.organisation, p.contactEmail is not null, p.avatarUpdatedAt)
			from UserProfile p where p.subject in :subjects""")
	List<OwnerProfile> findOwnerProfiles(@Param("subjects") Collection<String> subjects);

	Optional<UserProfile> findByPublicId(String publicId);

	UserProfile save(UserProfile profile);

	/** Just the picture, so looking it up does not depend on lazy loading. */
	@Query("select p.avatar from UserProfile p where p.publicId = :publicId")
	Optional<byte[]> findAvatarByPublicId(@Param("publicId") String publicId);
}
