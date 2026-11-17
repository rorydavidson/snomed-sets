package org.snomed.valuesets.valueset;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Upgrades carry no access rules of their own. Callers must first load the value set
 * through {@link ValueSetRepository}, which checks that the caller may see it.
 */
public interface ValueSetUpgradeRepository extends Repository<ValueSetUpgrade, Long> {

	List<ValueSetUpgrade> findByValueSetIdOrderByUpgradedAtDesc(long valueSetId);

	Optional<ValueSetUpgrade> findByIdAndValueSetId(long id, long valueSetId);

	ValueSetUpgrade save(ValueSetUpgrade upgrade);
}
