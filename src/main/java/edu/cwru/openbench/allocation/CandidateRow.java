package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.DateRange;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A row of {@code candidate}: a Person selected for a Matched line, with their Shortlist position
 * (0 for a named Person). {@code requestId}, {@code teamId} (the requesting Team), and the line's
 * days are copied from the line so this module reads only its own tables.
 */
@Entity
@Table(name = "candidate")
class CandidateRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lineId;

    private Long requestId;

    private Long teamId;

    private Long memberId;

    private int position;

    private LocalDate startsOn;

    private LocalDate endsOn;

    protected CandidateRow() {}

    CandidateRow(Long lineId, Long requestId, Long teamId, Long memberId, int position, DateRange days) {
        this.lineId = lineId;
        this.requestId = requestId;
        this.teamId = teamId;
        this.memberId = memberId;
        this.position = position;
        this.startsOn = days.from();
        this.endsOn = days.to();
    }

    Long id() {
        return id;
    }

    Long lineId() {
        return lineId;
    }

    Long requestId() {
        return requestId;
    }

    Long teamId() {
        return teamId;
    }

    Long memberId() {
        return memberId;
    }

    int position() {
        return position;
    }

    /** Prior selections stay in the approval history after a line is abandoned or reopened. */
    private boolean retired;

    boolean retired() {
        return retired;
    }

    void retire() {
        retired = true;
    }

}
