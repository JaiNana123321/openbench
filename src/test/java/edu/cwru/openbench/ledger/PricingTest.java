package edu.cwru.openbench.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.settings.PriceBand;
import edu.cwru.openbench.settings.RefundSchedule;
import edu.cwru.openbench.settings.RefundStep;
import edu.cwru.openbench.settings.TypePricing;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The Quote formula and the Refund step function, with the demo world's prices (no database). */
class PricingTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    /** The demo world's bands: Monday to Friday 09:00 to 17:00 at 1.5, the whole weekend at 0.5. */
    private static final List<PriceBand> DEMO_BANDS = List.of(
            new PriceBand(WEEKDAYS, LocalTime.of(9, 0), LocalTime.of(17, 0), new BigDecimal("1.500")),
            new PriceBand(WEEKEND, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, new BigDecimal("0.500")));

    private static final RefundSchedule DEMO_REFUNDS = new RefundSchedule(List.of(
            new RefundStep(Duration.ZERO, new BigDecimal("0.25")),
            new RefundStep(Duration.ofDays(7), new BigDecimal("0.75"))));

    private static final TypePricing MEETING_ROOM = pricing("10.00", DEMO_BANDS);
    private static final TypePricing GPU = new TypePricing(Credits.of("5.00"), DEMO_BANDS, DEMO_REFUNDS, null);

    private static final BigDecimal PREMIUM = new BigDecimal("2.0");

    @Nested
    @DisplayName("Ledger.Price.Formula: the demo story's Quotes")
    class DemoStory {

        @Test
        void quotesRoomAOnTuesdayAfternoonAt30() {
            // 2 h x 10 x 1.5
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 27, 14), at(2026, 10, 27, 16))).isEqualTo(Credits.of("30.00"));
        }

        @Test
        void quotesFourGpuUnitsFromTuesdayToThursdayAt1120() {
            // 16 peak hours and 32 other hours: 4 x 5 x (16 x 1.5 + 32)
            assertThat(quote(4, GPU, at(2026, 10, 27, 9), at(2026, 10, 29, 9))).isEqualTo(Credits.of("1120.00"));
        }

        @Test
        void quotesBobsMicroscopeAndRoomB() {
            TypePricing microscope = pricing("40.00", DEMO_BANDS);
            assertThat(quote(1, microscope, at(2026, 10, 28, 9), at(2026, 10, 28, 17))).isEqualTo(Credits.of("480.00"));
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 28, 9), at(2026, 10, 28, 17))).isEqualTo(Credits.of("120.00"));
        }
    }

    @Nested
    @DisplayName("Ledger.Price.Band: which band an hour falls in")
    class Bands {

        @Test
        void startsTheWeekdayBandAtNine() {
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 27, 8), at(2026, 10, 27, 9))).isEqualTo(Credits.of("10.00"));
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 27, 9), at(2026, 10, 27, 10))).isEqualTo(Credits.of("15.00"));
        }

        @Test
        void endsTheWeekdayBandBeforeFive() {
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 27, 16), at(2026, 10, 27, 17))).isEqualTo(Credits.of("15.00"));
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 27, 17), at(2026, 10, 27, 18))).isEqualTo(Credits.of("10.00"));
        }

        @Test
        void coversTheWholeWeekendDayWhenTheBandEndsAtMidnight() {
            // Saturday 00:00 to Sunday 00:00: 24 h x 10 x 0.5; Sunday's last hour is in the band too.
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 24, 0), at(2026, 10, 25, 0))).isEqualTo(Credits.of("120.00"));
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 25, 23), at(2026, 10, 26, 0))).isEqualTo(Credits.of("5.00"));
            // Friday 23:00 is unbanded, Saturday 00:00 is in the weekend band.
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 23, 23), at(2026, 10, 24, 1))).isEqualTo(Credits.of("15.00"));
        }

        @Test
        void countsAnHourNoBandCoversAtOne() {
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 26, 18), at(2026, 10, 26, 20))).isEqualTo(Credits.of("20.00"));
            assertThat(quote(3, pricing("10.00", List.of()), at(2026, 10, 27, 9), at(2026, 10, 27, 11)))
                    .isEqualTo(Credits.of("60.00"));
        }

        @Test
        void countsTheRealHoursOfTheDayDaylightSavingTimeEnds() {
            // Sunday 2026-11-01 has 25 hours in New York: Saturday and Sunday are 49 weekend hours.
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 31, 0), at(2026, 11, 2, 0))).isEqualTo(Credits.of("245.00"));
            // Friday 16:00 EDT to Monday 10:00 EST: 1 peak, 7 unbanded, 49 weekend, 9 unbanded, 1 peak
            // hours, read on the local clock on both sides of the change: 1.5 + 7 + 24.5 + 9 + 1.5 = 43.5.
            assertThat(quote(1, MEETING_ROOM, at(2026, 10, 30, 16), at(2026, 11, 2, 10))).isEqualTo(Credits.of("435.00"));
        }

        @Test
        void addsEveryOccurrenceOfARecurringLine() {
            LineSpec weekly = new LineSpec(1, List.of(
                    new TimeWindow(at(2026, 10, 27, 14), at(2026, 10, 27, 16)),
                    new TimeWindow(at(2026, 11, 3, 14), at(2026, 11, 3, 16))), false, ZONE);
            assertThat(Pricing.quote(weekly, MEETING_ROOM, PREMIUM).amount()).isEqualTo(Credits.of("60.00"));
        }

        @Test
        void refusesAWindowOffTheHour() {
            LineSpec halfHour = new LineSpec(1, List.of(
                    new TimeWindow(at(2026, 10, 27, 14).plusSeconds(1800), at(2026, 10, 27, 16))), false, ZONE);
            assertThatThrownBy(() -> Pricing.quote(halfHour, MEETING_ROOM, PREMIUM))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Ledger.Price.Premium and rounding")
    class PremiumAndRounding {

        @Test
        void multipliesAPriorityLineByThePremium() {
            LineSpec priority = new LineSpec(1, List.of(new TimeWindow(at(2026, 10, 27, 14), at(2026, 10, 27, 16))), true, ZONE);
            assertThat(Pricing.quote(priority, MEETING_ROOM, PREMIUM).amount()).isEqualTo(Credits.of("60.00"));
            assertThat(Pricing.quote(priority, MEETING_ROOM, new BigDecimal("1.25")).amount()).isEqualTo(Credits.of("37.50"));
        }

        @Test
        void leavesAStandardLineAtItsPrice() {
            LineSpec standard = new LineSpec(1, List.of(new TimeWindow(at(2026, 10, 27, 14), at(2026, 10, 27, 16))), false, ZONE);
            assertThat(Pricing.quote(standard, MEETING_ROOM, PREMIUM).amount()).isEqualTo(Credits.of("30.00"));
        }

        @Test
        void roundsOnceForTheWholeLine() {
            // Each hour is 0.05 x 1.333 = 0.06665: rounding every hour would give 3 x 0.07 = 0.21.
            TypePricing odd = pricing("0.05", List.of(
                    new PriceBand(EnumSet.allOf(DayOfWeek.class), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, new BigDecimal("1.333"))));
            assertThat(quote(1, odd, at(2026, 10, 27, 9), at(2026, 10, 27, 12))).isEqualTo(Credits.of("0.20"));
        }

        @Test
        void roundsHalfUp() {
            // 0.01 x 1.5 = 0.015 for one peak hour.
            assertThat(quote(1, pricing("0.01", DEMO_BANDS), at(2026, 10, 27, 9), at(2026, 10, 27, 10)))
                    .isEqualTo(Credits.of("0.02"));
        }
    }

    @Nested
    @DisplayName("Release.Refund: the step function")
    class Refunds {

        private final Credits charge = Credits.of("45.00");

        @Test
        void returnsTheSevenDayStepAtOrAboveSevenDays() {
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ofDays(10).plusHours(4))).isEqualTo(Credits.of("33.75"));
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ofDays(7))).isEqualTo(Credits.of("33.75"));
        }

        @Test
        void returnsTheZeroStepBelowSevenDays() {
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ofDays(7).minusSeconds(1))).isEqualTo(Credits.of("11.25"));
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ofHours(1))).isEqualTo(Credits.of("11.25"));
        }

        @Test
        void returnsNothingOnceThePartHasStarted() {
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ZERO)).isEqualTo(Credits.ZERO);
            assertThat(Pricing.refund(charge, DEMO_REFUNDS, Duration.ofHours(-2))).isEqualTo(Credits.ZERO);
        }

        @Test
        void returnsNothingBelowEveryStep() {
            RefundSchedule weekAhead = new RefundSchedule(List.of(new RefundStep(Duration.ofDays(7), new BigDecimal("0.75"))));
            assertThat(Pricing.refund(charge, weekAhead, Duration.ofDays(1))).isEqualTo(Credits.ZERO);
            assertThat(Pricing.refund(charge, RefundSchedule.NONE, Duration.ofDays(30))).isEqualTo(Credits.ZERO);
        }

        @Test
        void roundsDown() {
            // 45.01 x 0.75 = 33.7575
            assertThat(Pricing.refund(Credits.of("45.01"), DEMO_REFUNDS, Duration.ofDays(8))).isEqualTo(Credits.of("33.75"));
        }

        @Test
        void neverReturnsTheWholeCharge() {
            RefundSchedule almostAll = new RefundSchedule(List.of(new RefundStep(Duration.ZERO, new BigDecimal("0.999"))));
            assertThat(Pricing.refund(Credits.of("100.00"), almostAll, Duration.ofDays(30))).isLessThan(Credits.of("100.00"));

            RefundSchedule all = new RefundSchedule(List.of(new RefundStep(Duration.ZERO, BigDecimal.ONE)));
            assertThatThrownBy(() -> Pricing.refund(Credits.of("100.00"), all, Duration.ofDays(30)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static TypePricing pricing(String unitPrice, List<PriceBand> bands) {
        return new TypePricing(Credits.of(unitPrice), bands, DEMO_REFUNDS, Duration.ofHours(24));
    }

    private static Credits quote(int quantity, TypePricing pricing, Instant start, Instant end) {
        LineSpec line = new LineSpec(quantity, List.of(new TimeWindow(start, end)), false, ZONE);
        return Pricing.quote(line, pricing, PREMIUM).amount();
    }

    /** An hour on the company clock. */
    private static Instant at(int year, int month, int day, int hour) {
        return LocalDateTime.of(year, month, day, hour, 0).atZone(ZONE).toInstant();
    }
}
