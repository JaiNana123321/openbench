package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.RuleViolation;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A row of {@code request}: one Team's Request. Package-private, so other modules see {@link Request}. */
@Entity
@Table(name = "request")
class RequestRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long teamId;

    @Enumerated(EnumType.STRING)
    private RequestState state;

    /** Set at submission, cleared only by reopening for edit. */
    private Instant submittedAt;

    /** The end of the Objection Window, set when the Request first enters Pending. */
    private Instant windowEndsAt;

    private Instant createdAt;

    private Long createdBy;

    protected RequestRow() {}

    /** A new Draft of the Team. */
    RequestRow(Long teamId, Instant createdAt, Long createdBy) {
        this.teamId = teamId;
        this.state = RequestState.DRAFT;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    Long teamId() {
        return teamId;
    }

    RequestState state() {
        return state;
    }

    Instant submittedAt() {
        return submittedAt;
    }

    Instant windowEndsAt() {
        return windowEndsAt;
    }

    Instant createdAt() {
        return createdAt;
    }

    boolean isDraft() {
        return state == RequestState.DRAFT;
    }

    /** Draft to Submitted: the submission time, which orders the waitlist, is set. */
    void submit(Instant at) {
        moveTo(RequestState.SUBMITTED);
        this.submittedAt = at;
    }

    /**
     * Changes the state along a transition of design Table "Request transitions".
     *
     * @throws RuleViolation {@code WRONG_STATE} if no transition leads there from the current state
     */
    void moveTo(RequestState next) {
        if (!state.canBecome(next)) {
            throw new RuleViolation("WRONG_STATE", "Request " + id + " is " + state.name().toLowerCase()
                    + "; it cannot become " + next.name().toLowerCase() + ".");
        }
        this.state = next;
    }
}
