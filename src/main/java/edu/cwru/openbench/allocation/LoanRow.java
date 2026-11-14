package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.DateRange;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A row of {@code loan}: a Person lent to another Team for whole days, inclusive at both ends, with
 * {@code daysLent} their Monday-to-Friday days. Formed by {@link MatchedAllocator#commit}; the
 * {@code loan_one_at_a_time} constraint keeps a Person in at most one Loan on any day.
 */
@Entity
@Table(name = "loan")
class LoanRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long lineId;

    private Long memberId;

    private Long lendingTeamId;

    private Long borrowingTeamId;

    private LocalDate startsOn;

    private LocalDate endsOn;

    private int daysLent;

    private boolean endedEarly;

    protected LoanRow() {}

    LoanRow(Long lineId, Long memberId, Long lendingTeamId, Long borrowingTeamId, DateRange days) {
        this.lineId = lineId;
        this.memberId = memberId;
        this.lendingTeamId = lendingTeamId;
        this.borrowingTeamId = borrowingTeamId;
        this.startsOn = days.from();
        this.endsOn = days.to();
        this.daysLent = days.weekdays();
    }

    Long id() {
        return id;
    }

    Long lineId() {
        return lineId;
    }

    Long memberId() {
        return memberId;
    }

    Long lendingTeamId() {
        return lendingTeamId;
    }

    Long borrowingTeamId() {
        return borrowingTeamId;
    }

    DateRange days() {
        return new DateRange(startsOn, endsOn);
    }

    int daysLent() {
        return daysLent;
    }

    boolean endedEarly() {
        return endedEarly;
    }

    /**
     * Ends the Loan early (SRS Release.Loan.Prorate): {@code newEnd} becomes its last day, the
     * person-days are counted again, and {@code endedEarly} is set. The row stays in
     * {@code loan_one_at_a_time} for the days it lent.
     *
     * @throws IllegalArgumentException unless {@code newEnd} lies on or after the first day and
     *     before the current last day
     */
    void endEarly(LocalDate newEnd) {
        if (newEnd.isBefore(startsOn) || !newEnd.isBefore(endsOn)) {
            throw new IllegalArgumentException("Loan " + id + " runs from " + startsOn + " to " + endsOn
                    + "; it cannot end early on " + newEnd + ".");
        }
        DateRange shortened = new DateRange(startsOn, newEnd);
        this.endsOn = newEnd;
        this.daysLent = shortened.weekdays();
        this.endedEarly = true;
    }
}
