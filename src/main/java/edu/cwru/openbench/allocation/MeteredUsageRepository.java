package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** The usage side of {@code metered_allocation} (issue #24): listing ALLOCATED rows and recording units used. */
interface MeteredUsageRepository extends JpaRepository<MeteredAllocationRow, Long> {

    /** The ALLOCATED rows of these types whose window overlaps {@code [from, to)}, by start, then id. */
    @Query("""
            select m from MeteredAllocationRow m
            where m.typeId in :typeIds and m.state = edu.cwru.openbench.allocation.RowState.ALLOCATED
              and m.startsAt < :to and m.endsAt > :from
            order by m.startsAt, m.id""")
    List<MeteredAllocationRow> allocatedOverlapping(Collection<Long> typeIds, Instant from, Instant to);

    /**
     * Writes only the usage columns, so a recording never writes back a stale state, window, or units
     * that an allocator changed meanwhile.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MeteredAllocationRow m
            set m.usedUnits = :usedUnits, m.usageRecordedAt = :at, m.usageRecordedBy = :by
            where m.id = :id""")
    int recordUsage(Long id, int usedUnits, Instant at, Long by);
}
