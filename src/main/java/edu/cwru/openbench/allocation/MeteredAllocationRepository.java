package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MeteredAllocationRepository extends JpaRepository<MeteredAllocationRow, Long> {

    List<MeteredAllocationRow> findByLineIdAndStateIn(Long lineId, Collection<RowState> states);

    /** The type's rows in these states that overlap {@code [startsAt, endsAt)}, by id. */
    @Query("""
            select m from MeteredAllocationRow m
            where m.typeId = :typeId and m.state in :states and m.startsAt < :endsAt and m.endsAt > :startsAt
            order by m.id""")
    List<MeteredAllocationRow> overlapping(Long typeId, Collection<RowState> states, Instant startsAt, Instant endsAt);
}
