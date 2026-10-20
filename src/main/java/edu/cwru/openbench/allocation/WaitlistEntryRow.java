package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code waitlist_entry}: one waiting Required line. {@code joinedAt} holds the Request's
 * submission time, not the moment of joining, because the waitlist is ordered by submission time.
 */
@Entity
@Table(name = "waitlist_entry")
class WaitlistEntryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lineId;

    private Long typeId;

    private Instant startsAt;

    private Instant endsAt;

    private boolean priority;

    private boolean bumped;

    private Instant joinedAt;

    protected WaitlistEntryRow() {}

    WaitlistEntryRow(Long lineId, Long typeId, Instant startsAt, Instant endsAt, boolean priority, boolean bumped,
            Instant joinedAt) {
        this.lineId = lineId;
        this.typeId = typeId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.priority = priority;
        this.bumped = bumped;
        this.joinedAt = joinedAt;
    }

    WaitingLine toWaitingLine() {
        return new WaitingLine(new RequestLineId(lineId), new ResourceTypeId(typeId), new TimeWindow(startsAt, endsAt),
                priority, bumped, joinedAt);
    }
}
