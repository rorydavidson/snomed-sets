package org.snomed.valuesets.valueset;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Versions carry no access rules of their own. Callers must first load the value set
 * through {@link ValueSetRepository}, which checks that the caller may see it.
 */
public interface ValueSetVersionRepository extends Repository<ValueSetVersion, Long> {

	List<ValueSetVersion> findByValueSetIdOrderByVersionNumberDesc(long valueSetId);

	Optional<ValueSetVersion> findByValueSetIdAndVersionNumber(long valueSetId, int versionNumber);

	/** Every version of the given value sets, newest first within each set. */
	List<ValueSetVersion> findByValueSetIdInOrderByVersionNumberDesc(Collection<Long> valueSetIds);

	ValueSetVersion save(ValueSetVersion version);
}
