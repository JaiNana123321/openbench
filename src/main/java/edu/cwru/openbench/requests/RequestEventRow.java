package edu.cwru.openbench.requests;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code request_event}: one state change of a Request or of one of its lines, written in
 * the same transaction. Insert-only: the application role may not update or delete one (V7). A null
 * actor is the system.
 */
@Entity
@Table(name = "request_event")
class RequestEventRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long requestId;

    private Long lineId;

    private String fromState;

    private String toState;

    private Long actorId;

    private Instant at;

    private String note;

    private Instant createdAt;

    private Long createdBy;

    protected RequestEventRow() {}

    /** A change of the Request ({@code lineId} null) or of one line; {@code fromState} null for a new one. */
    RequestEventRow(Long requestId, Long lineId, String fromState, String toState, Long actorId, Instant at, String note) {
        this.requestId = requestId;
        this.lineId = lineId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorId = actorId;
        this.at = at;
        this.note = note;
        this.createdAt = at;
        this.createdBy = actorId;
    }

    Long id() {
        return id;
    }

    Long lineId() {
        return lineId;
    }

    String fromState() {
        return fromState;
    }

    String toState() {
        return toState;
    }

    Long actorId() {
        return actorId;
    }

    Instant at() {
        return at;
    }

    String note() {
        return note;
    }
}
