package edu.cwru.openbench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The schema plus every demo seed (profile {@code demo}) migrate cleanly. Runs in a database of its
 * own in the shared container, so the demo rows never meet the other tests' rows.
 */
class DemoSeedTest {

    private static final String DATABASE = "demo_seed";

    private static JdbcTemplate demo;

    @BeforeAll
    static void migrateASeparateDatabaseWithTheDemoSeeds() throws SQLException {
        try (Connection owner = DriverManager.getConnection(
                        PostgresTest.POSTGRES.getJdbcUrl(), PostgresTest.POSTGRES.getUsername(), PostgresTest.POSTGRES.getPassword());
                var statement = owner.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        String url = "jdbc:postgresql://%s:%d/%s".formatted(
                PostgresTest.POSTGRES.getHost(), PostgresTest.POSTGRES.getMappedPort(5432), DATABASE);
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new Driver(), url, PostgresTest.POSTGRES.getUsername(), PostgresTest.POSTGRES.getPassword());
        // The same settings as application-demo.yaml.
        List<String> locations = new ArrayList<>(List.of("classpath:db/migration", "classpath:db/demo"));
        // Until the Inventory seed V1001 is merged, a test-only stand-in supplies the Resource Types
        // V1002 looks up; with the real V1001 present the stand-in is never applied.
        if (DemoSeedTest.class.getResource("/db/demo/V1001__demo_inventory.sql") == null) {
            locations.add("classpath:db/demo-stand-in");
        }
        Flyway.configure().dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .outOfOrder(true)
                .load()
                .migrate();
        demo = new JdbcTemplate(dataSource);
    }

    @Test
    void seedsTheTeamsAndMembersOfTheDemoWorld() {
        assertThat(demo.queryForList("""
                SELECT m.username || ' ' || m.name || ' ' || t.name || ' ' || m.email
                FROM member m JOIN team t ON t.id = m.team_id ORDER BY m.id""", String.class))
                .containsExactly(
                        "alice Alice Chen Platform alice@openbench.example",
                        "ben Ben Ortiz Platform ben@openbench.example",
                        "bob Bob Patel Research bob@openbench.example",
                        "rita Rita Gomez Research rita@openbench.example",
                        "oscar Oscar Reed Operations oscar@openbench.example",
                        "carol Carol Davis Operations carol@openbench.example",
                        "dave Dave Kim Operations dave@openbench.example",
                        "erin Erin Walsh Operations erin@openbench.example");
        // The /api/me example of the web contract is Alice: Member 1 of Team 1.
        assertThat(demo.queryForMap("SELECT m.id AS member, t.id AS team FROM member m JOIN team t ON t.id = m.team_id WHERE username = 'alice'"))
                .containsEntry("member", 1L).containsEntry("team", 1L);
    }

    @Test
    void grantsTheRolesAndManagersOfTheDemoWorld() {
        assertThat(demo.queryForList("SELECT m.username, r.role FROM member_role r JOIN member m ON m.id = r.member_id ORDER BY m.id"))
                .containsExactly(
                        Map.of("username", "alice", "role", "MANAGER"),
                        Map.of("username", "bob", "role", "MANAGER"),
                        Map.of("username", "oscar", "role", "MANAGER"),
                        Map.of("username", "carol", "role", "ADMIN"),
                        Map.of("username", "dave", "role", "RESOURCE_OWNER"),
                        Map.of("username", "erin", "role", "PLANNER"));
        assertThat(demo.queryForList("SELECT t.name || ' ' || m.username FROM team t JOIN member m ON m.id = t.manager_member_id ORDER BY t.id", String.class))
                .containsExactly("Platform alice", "Research bob", "Operations oscar");
    }

    @Test
    void seedsThePeriodsAndPlannerSettingsOfTheDemoWorld() {
        assertThat(demo.queryForList("SELECT starts_on || ' to ' || ends_on FROM period ORDER BY starts_on", String.class))
                .containsExactly("2026-10-01 to 2026-10-31", "2026-11-01 to 2026-11-30");
        assertThat(demo.queryForList("SELECT key || '=' || value FROM planner_setting ORDER BY key", String.class))
                .containsExactly("day_rate=400.00", "objection_window_working_days=2", "period_months=1", "priority_premium=2.0");
    }

    @Test
    void seedsThePricesBandsRefundStepsAndBumpCutoffsOfTheDemoWorld() {
        assertThat(demo.queryForList("""
                SELECT t.name || ' ' || p.unit_price || ' ' || coalesce(p.bump_cutoff_hours || 'h', 'none')
                FROM type_pricing p JOIN resource_type t ON t.id = p.type_id ORDER BY t.id""", String.class))
                .containsExactly("Meeting room 10.00 24h", "Confocal microscope 40.00 24h", "GPU 5.00 none");
        assertThat(demo.queryForList("""
                SELECT t.name || ' ' || b.weekdays || ' ' || to_char(b.start_time, 'HH24:MI') || '-'
                       || to_char(b.end_time, 'HH24:MI') || ' x' || b.multiplier
                FROM price_band b JOIN resource_type t ON t.id = b.type_id ORDER BY t.id, b.weekdays""", String.class))
                .containsExactly(
                        "Meeting room 12345 09:00-17:00 x1.500", "Meeting room 67 00:00-00:00 x0.500",
                        "Confocal microscope 12345 09:00-17:00 x1.500", "Confocal microscope 67 00:00-00:00 x0.500",
                        "GPU 12345 09:00-17:00 x1.500", "GPU 67 00:00-00:00 x0.500");
        assertThat(demo.queryForList("""
                SELECT t.name || ' ' || s.min_lead_hours || 'h ' || s.fraction
                FROM refund_step s JOIN resource_type t ON t.id = s.type_id ORDER BY t.id, s.min_lead_hours DESC""", String.class))
                .containsExactly(
                        "Meeting room 168h 0.750", "Meeting room 0h 0.250",
                        "Confocal microscope 168h 0.750", "Confocal microscope 0h 0.250",
                        "GPU 168h 0.750", "GPU 0h 0.250");
    }

    @Test
    void seedsResearchsQuotaOnGpu() {
        assertThat(demo.queryForList("""
                SELECT team.name || ' ' || t.name || ' ' || q.max_units
                FROM quota q JOIN team ON team.id = q.team_id JOIN resource_type t ON t.id = q.type_id""", String.class))
                .containsExactly("Research GPU 8");
    }

    @Test
    void seedsTheOctoberGrantsWithMatchingAccounts() {
        assertThat(demo.queryForList("""
                SELECT team.name || ' ' || g.amount || ' from ' || p.starts_on
                FROM grant_plan g JOIN team ON team.id = g.team_id JOIN period p ON p.id = g.period_id ORDER BY team.id""", String.class))
                .containsExactly("Platform 5000.00 from 2026-10-01", "Research 600.00 from 2026-10-01", "Operations 1000.00 from 2026-10-01");
        assertThat(demo.queryForList("""
                SELECT team.name || ' ' || x.kind || ' ' || x.amount || ' (' || x.granted_part || ' granted, ' || x.earned_part
                       || ' earned) in ' || p.starts_on || ' at ' || to_char(x.posted_at AT TIME ZONE 'America/New_York', 'YYYY-MM-DD HH24:MI')
                       || ' by ' || coalesce(x.actor_id::text, 'the system')
                FROM transaction x JOIN team ON team.id = x.team_id JOIN period p ON p.id = x.period_id ORDER BY team.id""", String.class))
                .containsExactly(
                        "Platform GRANT 5000.00 (5000.00 granted, 0.00 earned) in 2026-10-01 at 2026-10-01 00:00 by the system",
                        "Research GRANT 600.00 (600.00 granted, 0.00 earned) in 2026-10-01 at 2026-10-01 00:00 by the system",
                        "Operations GRANT 1000.00 (1000.00 granted, 0.00 earned) in 2026-10-01 at 2026-10-01 00:00 by the system");
        assertThat(demo.queryForList("""
                SELECT team.name || ' ' || a.granted || ' ' || a.earned || ' ' || a.held
                FROM account a JOIN team ON team.id = a.team_id ORDER BY team.id""", String.class))
                .containsExactly("Platform 5000.00 0.00 0.00", "Research 600.00 0.00 0.00", "Operations 1000.00 0.00 0.00");
    }

    @Test
    void refusesToSeedTheLedgerWhenAResourceTypeItNeedsIsMissing() throws IOException, SQLException {
        String seed;
        try (InputStream file = DemoSeedTest.class.getResourceAsStream("/db/demo/V1002__demo_settings_ledger.sql")) {
            seed = new String(file.readAllBytes(), UTF_8);
        }
        String guard = seed.substring(seed.indexOf("DO $$"), seed.indexOf("END $$;") + "END $$;".length());
        try (Connection connection = demo.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE resource_type SET name = 'Meeting rooms' WHERE name = 'Meeting room'");
                assertThatThrownBy(() -> statement.execute(guard))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("V1002 needs these Resource Types from V1001: Meeting room (EXCLUSIVE)");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void givesEveryMemberThePasswordDemo() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        List<String> hashes = demo.queryForList("SELECT password_hash FROM member", String.class);
        assertThat(hashes).hasSize(8).allSatisfy(hash -> {
            assertThat(hash).startsWith("{bcrypt}");
            assertThat(encoder.matches("demo", hash)).isTrue();
        });
    }
}
