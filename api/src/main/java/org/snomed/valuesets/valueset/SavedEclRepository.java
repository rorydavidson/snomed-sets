package org.snomed.valuesets.valueset;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Saved expressions carry no access rules of their own. Callers must first load the value set
 * through {@link ValueSetService}, which checks that the caller may see or change it.
 */
public interface SavedEclRepository extends Repository<SavedEcl, Long> {

	Optional<SavedEcl> findByValueSetId(long valueSetId);

	SavedEcl save(SavedEcl savedEcl);

	void delete(SavedEcl savedEcl);
}
