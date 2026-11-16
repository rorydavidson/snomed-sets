package org.snomed.valuesets.valueset;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Versions carry no access rules of their own. Callers must first load the value set
 * through {@link ValueSetRepository}, which checks that the caller may see it.
 */
public interface ValueSetVersionRepository extends Repository<ValueSetVersion, Long> {

	List<ValueSetVersion> findByValueSetIdOrderByVersionNumberDesc(long valueSetId);

	Optional<ValueSetVersion> findByValueSetIdAndVersionNumber(long valueSetId, int versionNumber);

	Optional<ValueSetVersion> findFirstByValueSetIdOrderByVersionNumberDesc(long valueSetId);

	/** The newest version of each of the given value sets, for those that have one. */
	@Query("""
			select v from ValueSetVersion v
			where v.valueSetId in :valueSetIds
			  and v.versionNumber = (select max(other.versionNumber) from ValueSetVersion other
			                         where other.valueSetId = v.valueSetId)
			""")
	List<ValueSetVersion> findLatestOfEach(@Param("valueSetIds") Collection<Long> valueSetIds);

	ValueSetVersion save(ValueSetVersion version);
}
