package org.snomed.valuesets.valueset;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Changes carry no access rules of their own; load the value set and its upgrade first. */
public interface ValueSetUpgradeChangeRepository extends Repository<ValueSetUpgradeChange, Long> {

	List<ValueSetUpgradeChange> findByUpgradeIdOrderByIdAsc(long upgradeId);

	/** How many changes of each type the given upgrades made, without loading the changes themselves. */
	@Query("""
			select c.upgradeId as upgradeId, c.type as type, count(c) as count
			from ValueSetUpgradeChange c
			where c.upgradeId in :upgradeIds
			group by c.upgradeId, c.type
			""")
	List<ChangeCount> countByType(@Param("upgradeIds") Collection<Long> upgradeIds);

	<S extends ValueSetUpgradeChange> List<S> saveAll(Iterable<S> changes);

	interface ChangeCount {
		Long getUpgradeId();

		ValueSetUpgradeChange.Type getType();

		long getCount();
	}
}
