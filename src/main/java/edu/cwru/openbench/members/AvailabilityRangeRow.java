package edu.cwru.openbench.members;

import edu.cwru.openbench.common.DateRange;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A row of {@code availability_range}: days, inclusive at both ends, on which the Member can be lent.
 * A Member's ranges never overlap or touch: {@link Availability#merge} joins them before they are saved.
 */
@Entity
@Table(name = "availability_range")
class AvailabilityRangeRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;

    private LocalDate fromDate;

    private LocalDate toDate;

    private Long createdBy;

    protected AvailabilityRangeRow() {}

    AvailabilityRangeRow(Long memberId, DateRange range, Long createdBy) {
        this.memberId = memberId;
        this.fromDate = range.from();
        this.toDate = range.to();
        this.createdBy = createdBy;
    }

    DateRange toRange() {
        return new DateRange(fromDate, toDate);
    }
}
