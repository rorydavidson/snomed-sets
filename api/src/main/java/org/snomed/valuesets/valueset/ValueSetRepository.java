package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every query here takes the caller's subject, so a value set the caller may
 * not see can never be loaded by accident.
 */
public interface ValueSetRepository extends Repository<ValueSet, Long> {

	@Query("""
			select v from ValueSet v
			where v.visibility = org.snomed.valuesets.valueset.ValueSet.Visibility.COMMUNITY
			   or v.ownerSubject = :subject
			order by v.updatedAt desc
			""")
	List<ValueSet> findAllVisibleTo(@Param("subject") String subject);

	@Query("""
			select v from ValueSet v
			where v.id = :id
			  and (v.visibility = org.snomed.valuesets.valueset.ValueSet.Visibility.COMMUNITY
			       or v.ownerSubject = :subject)
			""")
	Optional<ValueSet> findVisibleById(@Param("id") long id, @Param("subject") String subject);

	ValueSet save(ValueSet valueSet);

	void delete(ValueSet valueSet);
}
