package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.TimeWindow;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code metered_allocation}: the units a Metered line holds concurrently over its whole
 * window. No constraint can sum rows, so the type lock and the hourly query keep the pool and the
 * Quota (design Section 6.2).
 */
@Entity
@Table(name = "metered_allocation")
class MeteredAllocationRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lineId;

    private Long teamId;

    private Long requestId;

    private Long typeId;

    private Integer units;

    private Instant startsAt;

    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    private RowState state;

    protected MeteredAllocationRow() {}

    MeteredAllocationRow(Long lineId, Long teamId, Long requestId, Long typeId, int units, Instant startsAt,
            Instant endsAt, RowState state) {
        this.lineId = lineId;
        this.teamId = teamId;
        this.requestId = requestId;
        this.typeId = typeId;
        this.units = units;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.state = state;
    }

    Long id() {
        return id;
    }

    RowState state() {
        return state;
    }

    void changeState(RowState state) {
        this.state = state;
    }

    TimeWindow window() {
        return new TimeWindow(startsAt, endsAt);
    }

    int units() {
        return units;
    }

    /** A RELEASED row for this row's units from {@code newEnd} to its end. */
    MeteredAllocationRow tailFrom(Instant newEnd) {
        return new MeteredAllocationRow(lineId, teamId, requestId, typeId, units, newEnd, endsAt, RowState.RELEASED);
    }

    /** A RELEASED row for the units given back over this row's whole window. */
    MeteredAllocationRow unitsGivenBack(int newUnits) {
        return new MeteredAllocationRow(lineId, teamId, requestId, typeId, units - newUnits, startsAt, endsAt,
                RowState.RELEASED);
    }

    /** Shortens the row to end at {@code newEnd}; its start stays. */
    void endAt(Instant newEnd) {
        this.endsAt = newEnd;
    }

    void keepUnits(int newUnits) {
        this.units = newUnits;
    }

    // ---------------------------------------------------------------- Actual usage (issue #24)

    /** The units recorded as consumed, null until a Resource Owner records them; never read by the hourly check. */
    private Integer usedUnits;

    private Instant usageRecordedAt;

    private Long usageRecordedBy;

    Long teamId() {
        return teamId;
    }

    Long requestId() {
        return requestId;
    }

    Long typeId() {
        return typeId;
    }

    Integer usedUnits() {
        return usedUnits;
    }

    Instant usageRecordedAt() {
        return usageRecordedAt;
    }

    Long usageRecordedBy() {
        return usageRecordedBy;
    }
}
