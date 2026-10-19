package edu.cwru.openbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/demo")
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
    void seedsTheResourceTypesAndAttributesOfTheDemoWorld() {
        assertThat(demo.queryForList("SELECT name || ' ' || allocation_class FROM resource_type ORDER BY id", String.class))
                .containsExactly("Person MATCHED", "Meeting room EXCLUSIVE", "Confocal microscope EXCLUSIVE", "GPU METERED");
        assertThat(demo.queryForList("""
                SELECT t.name || ': ' || a.name || ' ' || a.kind || coalesce(' ' || array_to_string(a.allowed_values, '/'), '')
                FROM resource_type_attribute a JOIN resource_type t ON t.id = a.type_id ORDER BY a.id""", String.class))
                .containsExactly(
                        "Meeting room: seats NUMBER",
                        "Meeting room: wing LIST North/South",
                        "Confocal microscope: lab TEXT",
                        "GPU: model LIST A100/H100");
    }

    @Test
    void seedsTheResourcesOfTheDemoWorldOwnedByDave() {
        assertThat(demo.queryForList("""
                SELECT r.name || ' ' || t.name || ' ' || r.units || ' ' || m.username || ' '
                       || (SELECT string_agg(a.name || '=' || v.value, ',' ORDER BY a.id)
                           FROM resource_attribute_value v JOIN resource_type_attribute a ON a.id = v.attribute_id
                           WHERE v.resource_id = r.id)
                FROM resource r JOIN resource_type t ON t.id = r.type_id JOIN member m ON m.id = r.owner_member_id
                WHERE r.retired_at IS NULL ORDER BY r.id""", String.class))
                .containsExactly(
                        "Room A Meeting room 1 dave seats=8,wing=North",
                        "Room B Meeting room 1 dave seats=12,wing=South",
                        "Microscope 1 Confocal microscope 1 dave lab=Imaging lab",
                        "gpu-node-a GPU 8 dave model=H100",
                        "gpu-node-b GPU 8 dave model=A100");
        // Pool Capacity of GPU, as InventoryQueries.poolCapacity computes it.
        assertThat(demo.queryForObject("""
                SELECT sum(r.units) FROM resource r JOIN resource_type t ON t.id = r.type_id
                WHERE t.name = 'GPU' AND r.retired_at IS NULL""", Integer.class)).isEqualTo(16);
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
