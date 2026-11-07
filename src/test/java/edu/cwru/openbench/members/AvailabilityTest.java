package edu.cwru.openbench.members;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.common.DateRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How availability ranges are merged on save, without a database. */
class AvailabilityTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 2);

    @Test
    void mergesOverlappingAndTouchingRangesAndKeepsGapsApart() {
        List<DateRange> merged = Availability.merge(List.of(
                days(20, 22), days(0, 4), days(2, 3), days(5, 6), days(8, 9), days(9, 12)));

        assertThat(merged).containsExactly(days(0, 6), days(8, 12), days(20, 22));
    }

    @Test
    void keepsAnEmptyListEmpty() {
        assertThat(Availability.merge(List.of())).isEmpty();
    }

    private static DateRange days(int from, int to) {
        return new DateRange(DAY.plusDays(from), DAY.plusDays(to));
    }
}
