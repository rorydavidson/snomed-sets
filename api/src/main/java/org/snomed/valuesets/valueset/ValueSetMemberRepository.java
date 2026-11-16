package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Members carry no access rules of their own. Callers must first load the value set
 * through {@link ValueSetRepository}, which checks that the caller may see it.
 */
public interface ValueSetMemberRepository extends Repository<ValueSetMember, Long> {

	List<ValueSetMember> findByValueSetIdAndVersionNumberOrderByPreferredTermAscConceptIdAsc(long valueSetId,
			int versionNumber);

	@Query(value = """
			SELECT id, value_set_id, version_number, concept_id, preferred_term, preferred_term_language,
			       fully_specified_name
			FROM value_set_member
			WHERE value_set_id = :valueSetId AND version_number = :versionNumber
			ORDER BY preferred_term, concept_id
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<ValueSetMember> findPage(@Param("valueSetId") long valueSetId, @Param("versionNumber") int versionNumber,
			@Param("offset") int offset, @Param("limit") int limit);

	long countByValueSetIdAndVersionNumber(long valueSetId, int versionNumber);

	@Query("select m.conceptId from ValueSetMember m where m.valueSetId = :valueSetId and m.versionNumber = :versionNumber")
	Set<String> findConceptIds(@Param("valueSetId") long valueSetId, @Param("versionNumber") int versionNumber);

	@Modifying
	@Query("delete from ValueSetMember m where m.valueSetId = :valueSetId and m.versionNumber = 0")
	void deleteWorkingList(@Param("valueSetId") long valueSetId);

	/** Freezes the list being edited as the content of a published version. */
	@Modifying
	@Query(value = """
			INSERT INTO value_set_member (value_set_id, version_number, concept_id, preferred_term,
			                              preferred_term_language, fully_specified_name)
			SELECT value_set_id, :versionNumber, concept_id, preferred_term, preferred_term_language,
			       fully_specified_name
			FROM value_set_member
			WHERE value_set_id = :valueSetId AND version_number = 0
			""", nativeQuery = true)
	void copyWorkingListToVersion(@Param("valueSetId") long valueSetId, @Param("versionNumber") int versionNumber);

	<S extends ValueSetMember> List<S> saveAll(Iterable<S> members);
}
