package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.PeriodId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Optional;

/** A row of {@code period}. Other modules see {@link Period}. */
@Entity
@Table(name = "period")
class PeriodRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate startsOn;

    private LocalDate endsOn;

    private Long createdBy;

    protected PeriodRow() {}

    PeriodRow(LocalDate startsOn, LocalDate endsOn, Long createdBy) {
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    LocalDate startsOn() {
        return startsOn;
    }

    LocalDate endsOn() {
        return endsOn;
    }

    Period toPeriod(Optional<PeriodRow> previous) {
        return new Period(new PeriodId(id), startsOn, endsOn, previous.map(row -> new PeriodId(row.id)));
    }
}
