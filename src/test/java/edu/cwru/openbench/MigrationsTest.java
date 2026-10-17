package edu.cwru.openbench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The production migrations on PostgreSQL 16: what they create, and that Hibernate accepts it. */
class MigrationsTest extends PostgresTest {

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
    @DisplayName("Inventory.Type.Define: the built-in Person type is seeded as Matched")
    void seedsThePersonType() {
        assertThat(jdbc.queryForObject(
                "SELECT allocation_class FROM resource_type WHERE name = 'Person'", String.class)).isEqualTo("MATCHED");
    }
}
