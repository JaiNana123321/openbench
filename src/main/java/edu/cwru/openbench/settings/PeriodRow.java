package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.PeriodId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Optional;

/** A row of {@code period}. Other modules see {@link Period}. */
@Entity
@Table(name = "period")
class PeriodRow {

    @Id
    private Long id;

    private LocalDate startsOn;

    private LocalDate endsOn;

    protected PeriodRow() {}

    Long id() {
        return id;
    }

    LocalDate startsOn() {
        return startsOn;
    }

    Period toPeriod(Optional<PeriodRow> previous) {
        return new Period(new PeriodId(id), startsOn, endsOn, previous.map(row -> new PeriodId(row.id)));
    }
}
