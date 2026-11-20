package org.snomed.valuesets.map;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * Every query here takes the owner's subject, so a map belonging to someone else can never be
 * loaded by accident.
 */
public interface CodeMapRepository extends Repository<CodeMap, Long> {

	List<CodeMap> findByOwnerSubjectOrderByUpdatedAtDesc(String ownerSubject);

	Optional<CodeMap> findByIdAndOwnerSubject(long id, String ownerSubject);

	CodeMap save(CodeMap map);

	void delete(CodeMap map);
}
