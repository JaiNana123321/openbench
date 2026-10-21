package edu.cwru.openbench.allocation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code exclusive_occurrence}: one occurrence of an Exclusive line on one instance. The
 * constraint {@code exclusive_occurrence_no_overlap} keeps live rows on one Resource apart.
 */
@Entity
@Table(name = "exclusive_occurrence")
class ExclusiveOccurrenceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lineId;

    private Long teamId;

    private Long requestId;

    private Long resourceId;

    private Instant startsAt;

    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    private RowState state;

    protected ExclusiveOccurrenceRow() {}

    ExclusiveOccurrenceRow(Long lineId, Long teamId, Long requestId, Long resourceId, Instant startsAt, Instant endsAt,
            RowState state) {
        this.lineId = lineId;
        this.teamId = teamId;
        this.requestId = requestId;
        this.resourceId = resourceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.state = state;
    }

    Long id() {
        return id;
    }

    Long resourceId() {
        return resourceId;
    }

    RowState state() {
        return state;
    }

    void changeState(RowState state) {
        this.state = state;
    }
}
