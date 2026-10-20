package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WaitlistEntryRepository extends JpaRepository<WaitlistEntryRow, Long> {

    boolean existsByLineId(Long lineId);

    @Modifying(flushAutomatically = true)
    @Query("delete from WaitlistEntryRow w where w.lineId = :lineId")
    int deleteByLine(Long lineId);

    /** The type's entries whose windows overlap the half-open window, in waitlist order. */
    @Query("""
            select w from WaitlistEntryRow w
            where w.typeId = :typeId and w.startsAt < :endsAt and w.endsAt > :startsAt
            order by w.bumped desc, w.priority desc, w.joinedAt, w.id""")
    List<WaitlistEntryRow> overlapping(Long typeId, Instant startsAt, Instant endsAt);

    /**
     * One more than the entries ahead of the line: same type, overlapping window, and earlier in
     * waitlist order (Bumped first, then Priority, then submission time, then id). Null when the
     * line has no entry.
     */
    @Query(value = """
            SELECT 1 + (SELECT count(*) FROM waitlist_entry o
                        WHERE o.type_id = me.type_id AND o.id <> me.id
                          AND o.starts_at < me.ends_at AND o.ends_at > me.starts_at
                          AND (NOT o.bumped, NOT o.priority, o.joined_at, o.id)
                            < (NOT me.bumped, NOT me.priority, me.joined_at, me.id))
            FROM waitlist_entry me WHERE me.line_id = :lineId""", nativeQuery = true)
    Integer positionOf(Long lineId);
}
