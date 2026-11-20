package org.snomed.valuesets.map;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Rows are only reached through a map the caller has already been allowed to load. */
public interface CodeMapRowRepository extends Repository<CodeMapRow, Long> {

	List<CodeMapRow> findByMapIdAndVersionNumberOrderByPosition(long mapId, int versionNumber);

	List<CodeMapRow> findByMapIdAndVersionNumberAndIdInOrderByPosition(long mapId, int versionNumber,
			Collection<Long> ids);

	Optional<CodeMapRow> findByIdAndMapIdAndVersionNumber(long id, long mapId, int versionNumber);

	@Query("select r.sourceCode from CodeMapRow r where r.mapId = :mapId and r.versionNumber = 0")
	List<String> findWorkingCodes(@Param("mapId") long mapId);

	@Query("select coalesce(max(r.position), -1) from CodeMapRow r where r.mapId = :mapId and r.versionNumber = 0")
	int findLastWorkingPosition(@Param("mapId") long mapId);

	/** Map, status and number of rows, for the working rows of each map. */
	@Query("""
			select r.mapId, r.status, count(r) from CodeMapRow r
			where r.mapId in :mapIds and r.versionNumber = 0
			group by r.mapId, r.status
			""")
	List<Object[]> countWorkingRowsByStatus(@Param("mapIds") Collection<Long> mapIds);

	CodeMapRow save(CodeMapRow row);

	void delete(CodeMapRow row);
}
