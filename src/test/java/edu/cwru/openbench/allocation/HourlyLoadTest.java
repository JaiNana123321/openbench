package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.allocation.HourlyLoad.Excess;
import edu.cwru.openbench.allocation.HourlyLoad.Hour;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Metered.Pool.Check: HourlyLoad")
class HourlyLoadTest {

    private static final Instant TWO_PM = Instant.parse("2026-10-27T18:00:00Z");
    private static final Instant THREE_PM = TWO_PM.plusSeconds(3600);
    private static final Instant FOUR_PM = TWO_PM.plusSeconds(7200);

    /**
     * The design's worked example: a pool of 8 holds A (3 units, 10:00 to 15:00) and B (4 units,
     * 13:00 to 17:00, owned by the requesting Team); the line asks for 2 units from 14:00 to 17:00.
     */
    @Nested
    class WorkedExample {

        private final List<Hour> hours = List.of(
                new Hour(TWO_PM, 7, 4),
                new Hour(THREE_PM, 4, 4),
                new Hour(FOUR_PM, 4, 4));

        @Test
        void isPoolFullAtTwoOneUnitShort() {
            assertThat(HourlyLoad.firstExcess(hours, 2, 8, Optional.empty()))
                    .contains(new Excess(HourlyLoad.POOL_FULL, TWO_PM, 1, 8));
        }

        @Test
        void reportsThePoolBeforeAQuotaThatAlsoFails() {
            assertThat(HourlyLoad.firstExcess(hours, 2, 8, Optional.of(5)))
                    .contains(new Excess(HourlyLoad.POOL_FULL, TWO_PM, 1, 8));
        }

        @Test
        void failsTheQuotaOfFiveWhenThePoolHasRoom() {
            assertThat(HourlyLoad.firstExcess(hours, 2, 16, Optional.of(5)))
                    .contains(new Excess(HourlyLoad.QUOTA_EXCEEDED, TWO_PM, 1, 5));
        }

        @Test
        void fitsOnceAHasGivenBackTwoOClock() {
            List<Hour> afterRelease = List.of(new Hour(TWO_PM, 4, 4), new Hour(THREE_PM, 4, 4), new Hour(FOUR_PM, 4, 4));

            assertThat(HourlyLoad.firstExcess(afterRelease, 2, 8, Optional.empty())).isEmpty();
        }
    }

    @Test
    void fitsExactlyAtCapacity() {
        assertThat(HourlyLoad.firstExcess(List.of(new Hour(TWO_PM, 6, 0)), 2, 8, Optional.of(2))).isEmpty();
    }

    @Test
    void reportsTheEarliestFailingHour() {
        List<Hour> hours = List.of(new Hour(TWO_PM, 1, 0), new Hour(THREE_PM, 7, 0), new Hour(FOUR_PM, 8, 0));

        assertThat(HourlyLoad.firstExcess(hours, 2, 8, Optional.empty()))
                .contains(new Excess(HourlyLoad.POOL_FULL, THREE_PM, 1, 8));
    }

    @Test
    void findsTheFirstHourAboveAReducedCapacityAndThePeak() {
        List<Hour> hours = List.of(new Hour(TWO_PM, 3, 0), new Hour(THREE_PM, 7, 0), new Hour(FOUR_PM, 5, 0));

        assertThat(HourlyLoad.firstAbove(hours, 4)).contains(THREE_PM);
        assertThat(HourlyLoad.firstAbove(hours, 7)).isEmpty();
        assertThat(HourlyLoad.peak(hours)).isEqualTo(7);
        assertThat(HourlyLoad.peak(List.of())).isZero();
    }
}
