package edu.cwru.openbench.allocation;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code candidate}: a Person selected for a Matched line, with their Shortlist position
 * (0 for a named Person). {@code requestId} and {@code teamId} (the requesting Team) are copied from
 * the line so this module reads only its own tables.
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

    protected CandidateRow() {}

    CandidateRow(Long lineId, Long requestId, Long teamId, Long memberId, int position) {
        this.lineId = lineId;
        this.requestId = requestId;
        this.teamId = teamId;
        this.memberId = memberId;
        this.position = position;
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
}
