package edu.cwru.openbench.settings;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The settings snapshot other modules read, and the Periods. */
class SettingsServiceTest extends PostgresTest {

    @Autowired
    private SettingsService settings;

    @Autowired
    private Clock clock;

    private LocalDate today;
    private long currentPeriod;

    @BeforeEach
    void makeThePeriodOfToday() {
        today = LocalDate.now(clock);
        currentPeriod = testData().periodContaining(today);
    }

    @Test
    void readsTheCurrentPeriodByTheClock() {
        Period period = settings.current().currentPeriod();

        assertThat(period.id()).isEqualTo(new PeriodId(currentPeriod));
        assertThat(period.contains(today)).isTrue();
        assertThat(settings.currentPeriod()).isEqualTo(period);
    }

    @Test
    void readsTheSingleValuedSettingsWithTheirInstallationDefaults() {
        PlannerSettings current = settings.current();

        assertThat(current.dayRate()).isEqualTo(Credits.of("400.00"));
        assertThat(current.priorityPremium()).isEqualByComparingTo("2.0");
        assertThat(current.objectionWindowWorkingDays()).isEqualTo(2);
    }

    @Test
    void readsATypesPricesBandsRefundStepsAndBumpCutoff() {
        long room = type("EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price, bump_cutoff_hours) VALUES (?, 10.00, 24)", room);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '12345', '09:00', '17:00', 1.5)", room);
        jdbc.update("INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier) VALUES (?, '67', '00:00', '00:00', 0.5)", room);
        jdbc.update("INSERT INTO refund_step (type_id, min_lead_hours, fraction) VALUES (?, 168, 0.75), (?, 0, 0.25)", room, room);
        long gpu = type("METERED");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 5.00)", gpu);

        PlannerSettings current = settings.current();

        TypePricing roomPricing = current.pricingOf(new ResourceTypeId(room)).orElseThrow();
        assertThat(roomPricing.unitPrice()).isEqualTo(Credits.of("10.00"));
        assertThat(roomPricing.bumpCutoff()).isEqualTo(Duration.ofHours(24));
        assertThat(roomPricing.bands()).containsExactlyInAnyOrder(
                new PriceBand(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(17, 0), new BigDecimal("1.500")),
                new PriceBand(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, new BigDecimal("0.500")));
        assertThat(roomPricing.refundSchedule().steps()).containsExactly(
                new RefundStep(Duration.ZERO, new BigDecimal("0.250")),
                new RefundStep(Duration.ofDays(7), new BigDecimal("0.750")));

        TypePricing gpuPricing = current.pricingOf(new ResourceTypeId(gpu)).orElseThrow();
        assertThat(gpuPricing.bumpCutoff()).isNull();
        assertThat(gpuPricing.bands()).isEmpty();
        assertThat(gpuPricing.refundSchedule()).isEqualTo(RefundSchedule.NONE);
        assertThat(current.pricingOf(new ResourceTypeId(type("EXCLUSIVE")))).isEmpty();
    }

    @Test
    void readsTheQuotas() {
        long team = testData().team(unique("Research"));
        long gpu = type("METERED");
        jdbc.update("INSERT INTO quota (team_id, type_id, max_units) VALUES (?, ?, 8)", team, gpu);

        PlannerSettings current = settings.current();

        assertThat(current.quotaOf(new TeamId(team), new ResourceTypeId(gpu))).contains(8);
        assertThat(current.quotas()).containsEntry(new TeamTypeKey(new TeamId(team), new ResourceTypeId(gpu)), 8);
        assertThat(current.quotaOf(new TeamId(team), new ResourceTypeId(type("METERED")))).isEmpty();
    }

    @Test
    void readsEachTeamsGrantAsTheLatestPlanStartingNoLaterThanTheCurrentPeriod() {
        long earlier = testData().periodContaining(LocalDate.of(1990, 1, 1));
        long later = testData().period();
        long planned = testData().team(unique("Platform"));
        long recurring = testData().team(unique("Research"));
        long unplanned = testData().team(unique("Operations"));
        jdbc.update("INSERT INTO grant_plan (team_id, period_id, amount) VALUES (?, ?, 100.00), (?, ?, 5000.00), (?, ?, 9.00)",
                planned, earlier, planned, currentPeriod, planned, later);
        jdbc.update("INSERT INTO grant_plan (team_id, period_id, amount) VALUES (?, ?, 600.00)", recurring, earlier);

        PlannerSettings current = settings.current();

        assertThat(current.grants())
                .containsEntry(new TeamId(planned), Credits.of("5000.00"))
                .containsEntry(new TeamId(recurring), Credits.of("600.00"))
                .doesNotContainKey(new TeamId(unplanned));
    }

    @Test
    void listsThePeriodsByStartWithTheirPredecessors() {
        long farFuture = testData().period();

        List<Period> all = settings.periods();

        assertThat(all).extracting(Period::id).contains(new PeriodId(currentPeriod), new PeriodId(farFuture));
        assertThat(all).isSortedAccordingTo((a, b) -> a.startsOn().compareTo(b.startsOn()));
        assertThat(all.getFirst().previous()).isEmpty();
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).previous()).contains(all.get(i - 1).id());
        }
        Period found = settings.period(new PeriodId(farFuture));
        assertThat(all).contains(found);
    }

    @Test
    void refusesAnUnknownPeriod() {
        assertThatThrownBy(() -> settings.period(new PeriodId(Long.MAX_VALUE))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void returnsAnEmptyPreviousOnlyForTheFirstPeriod() {
        Period first = settings.periods().getFirst();
        assertThat(settings.period(first.id()).previous()).isEqualTo(Optional.empty());
    }

    private long type(String allocationClass) {
        return jdbc.queryForObject("INSERT INTO resource_type (name, allocation_class) VALUES (?, ?) RETURNING id",
                Long.class, unique("Type"), allocationClass);
    }
}
