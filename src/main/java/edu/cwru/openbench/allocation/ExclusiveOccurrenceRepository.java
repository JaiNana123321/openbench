package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ExclusiveOccurrenceRepository extends JpaRepository<ExclusiveOccurrenceRow, Long> {

    List<ExclusiveOccurrenceRow> findByLineIdAndStateIn(Long lineId, Collection<RowState> states);

    /** The instances the line's rows in these states are on, by id. */
    @Query("""
            select distinct o.resourceId from ExclusiveOccurrenceRow o
            where o.lineId = :lineId and o.state in :states order by o.resourceId""")
    List<Long> resourcesOfLine(Long lineId, Collection<RowState> states);

    /** Of these instances, those with a live occurrence overlapping the half-open window. */
    @Query("""
            select distinct o.resourceId from ExclusiveOccurrenceRow o
            where o.resourceId in :resourceIds and o.state in :live
              and o.startsAt < :endsAt and o.endsAt > :startsAt""")
    List<Long> busyAmong(Collection<Long> resourceIds, Collection<RowState> live, Instant startsAt, Instant endsAt);

    /** The ALLOCATED occurrences on these instances that overlap the window, as (resource, start, end). */
    @Query("""
            select new edu.cwru.openbench.allocation.OccurrenceSpan(o.resourceId, o.startsAt, o.endsAt)
            from ExclusiveOccurrenceRow o
            where o.resourceId in :resourceIds and o.state = edu.cwru.openbench.allocation.RowState.ALLOCATED
              and o.startsAt < :endsAt and o.endsAt > :startsAt""")
    List<OccurrenceSpan> allocatedAmong(Collection<Long> resourceIds, Instant startsAt, Instant endsAt);
}
