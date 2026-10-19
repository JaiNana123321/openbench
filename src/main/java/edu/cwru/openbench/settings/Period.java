package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.PeriodId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A Period: contiguous days, inclusive at both ends, over which granted credits live. {@code previous}
 * is the Period that ended before this one started, if there is one (the rollover needs it).
 * JSON: {@code {"id": 1, "startsOn": "2026-10-01", "endsOn": "2026-10-31", "previous": null}}.
 */
public record Period(PeriodId id, LocalDate startsOn, LocalDate endsOn, Optional<PeriodId> previous) {

    public Period {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(previous, "previous");
        new DateRange(startsOn, endsOn);
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(startsOn) && !day.isAfter(endsOn);
    }
}
