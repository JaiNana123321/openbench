package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.DateRange;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A row of {@code loan}: a Person lent to another Team for whole days, inclusive at both ends.
 * Loans are formed by a later increment (#6); this one reads them to build Shortlists.
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

    Long id() {
        return id;
    }

    Long memberId() {
        return memberId;
    }

    DateRange days() {
        return new DateRange(startsOn, endsOn);
    }
}
