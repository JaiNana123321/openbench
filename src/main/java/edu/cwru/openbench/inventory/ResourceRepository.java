package edu.cwru.openbench.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

interface ResourceRepository extends JpaRepository<ResourceRow, Long>, JpaSpecificationExecutor<ResourceRow> {

    List<ResourceRow> findByTypeIdAndRetiredAtIsNullOrderByName(Long typeId);

    /** Whether any Resource, retired or not, is of this type: then its Allocation Class is fixed. */
    boolean existsByTypeId(Long typeId);

    /** Pool Capacity: the units of the type's unretired Resources, summed on every call. */
    @Query("select coalesce(sum(r.units), 0) from ResourceRow r where r.typeId = :typeId and r.retiredAt is null")
    long sumOfUnits(Long typeId);

    /** Whether another Resource of the type has this name, ignoring case; pass 0 for a new Resource. */
    @Query("""
            select count(r) > 0 from ResourceRow r
            where r.typeId = :typeId and lower(r.name) = lower(:name) and r.id <> :exceptId""")
    boolean nameTaken(Long typeId, String name, long exceptId);
}
