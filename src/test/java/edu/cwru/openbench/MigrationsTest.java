package edu.cwru.openbench;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The production migrations on PostgreSQL 16: what they create, and that Hibernate accepts it. */
class MigrationsTest extends PostgresTest {

    @Autowired
    private Clock clock;

    @Autowired
    private SettingsService settings;

    @Test
    @DisplayName("SI-db: Flyway applies every migration and Hibernate validates the entities at start-up")
    void migratesAndValidatesAtStartUp() {
        // The context started, so Hibernate's ddl-auto=validate passed against the migrated schema.
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("Creates every Demo 1 table and none of the next increment's")
    void createsTheDemo1Tables() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'""", String.class))
                .containsExactlyInAnyOrder(
                        "flyway_schema_history",
                        "team", "member", "member_role", "skill", "member_skill", "availability_range",
                        "resource_type", "resource_type_attribute", "resource", "resource_attribute_value",
                        "period", "grant_plan", "type_pricing", "price_band", "refund_step", "quota",
                        "planner_setting", "setting_change",
                        "account", "transaction", "held_credit", "period_close",
                        "request", "request_line", "suggestion", "request_event",
                        "exclusive_occurrence", "metered_allocation", "waitlist_entry");
    }

    @Test
    @DisplayName("Ledger.Grant.Period: an installation has a current Period, the calendar month of today, and Periods ten years ahead")
    void writesTheInstallationPeriods() {
        // Asked at the same instant as the migration's first month, unless the test runs across a month end.
        LocalDate today = LocalDate.now(clock);
        Period current = settings.currentPeriod();
        assertThat(current.startsOn()).isEqualTo(today.withDayOfMonth(1));
        assertThat(current.endsOn()).isEqualTo(today.withDayOfMonth(1).plusMonths(1).minusDays(1));

        // Other tests add one-day Periods only in 1990 and from 2200 on, outside these ten years.
        List<Map<String, Object>> months = jdbc.queryForList("""
                SELECT starts_on, ends_on FROM period WHERE starts_on >= ? AND starts_on < ? ORDER BY starts_on""",
                current.startsOn(), current.startsOn().plusYears(10));
        assertThat(months).hasSize(120);
        for (int n = 0; n < months.size(); n++) {
            LocalDate start = current.startsOn().plusMonths(n);
            assertThat(months.get(n)).containsEntry("starts_on", Date.valueOf(start))
                    .containsEntry("ends_on", Date.valueOf(start.plusMonths(1).minusDays(1)));
        }
    }

    @Test
    @DisplayName("Inventory.Type.Define: the built-in Person type is seeded as Matched")
    void seedsThePersonType() {
        assertThat(jdbc.queryForObject(
                "SELECT allocation_class FROM resource_type WHERE name = 'Person'", String.class)).isEqualTo("MATCHED");
    }
}
