package edu.cwru.openbench;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The privileges of openbench_app, the role the application connects as in production (V7). */
@DisplayName("Ledger.Transaction.Kinds, QA-audit: the application role cannot change history")
class AppRoleGrantsTest extends PostgresTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void insertsATransactionButCannotUpdateOrDeleteIt() throws SQLException {
        long team = testData().team(unique("Team"));
        long period = testData().period();

        try (Connection app = connectAsApp()) {
            long id;
            try (PreparedStatement insert = app.prepareStatement("""
                    INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, posted_at)
                    VALUES (?, 'GRANT', 600.00, 600.00, 0, ?, ?) RETURNING id""")) {
                insert.setLong(1, team);
                insert.setLong(2, period);
                insert.setObject(3, OffsetDateTime.of(2026, 10, 1, 4, 0, 0, 0, ZoneOffset.UTC));
                try (ResultSet row = insert.executeQuery()) {
                    row.next();
                    id = row.getLong(1);
                }
            }

            assertThatThrownBy(() -> execute(app, "UPDATE transaction SET amount = 6000, granted_part = 6000 WHERE id = " + id))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> execute(app, "DELETE FROM transaction WHERE id = " + id))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE team_id = ?", String.class, team))
                .isEqualTo("600.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"transaction", "request_event", "setting_change"})
    void leavesTheHistoryTablesInsertOnly(String table) {
        assertThat(privilege(table, "SELECT")).isTrue();
        assertThat(privilege(table, "INSERT")).isTrue();
        assertThat(privilege(table, "UPDATE")).isFalse();
        assertThat(privilege(table, "DELETE")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "request", "request_line", "exclusive_occurrence", "account"})
    void mayChangeTheWorkingTables(String table) {
        assertThat(privilege(table, "UPDATE")).isTrue();
        assertThat(privilege(table, "DELETE")).isTrue();
    }

    private boolean privilege(String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege('openbench_app', ?, ?)", Boolean.class, table, privilege));
    }

    private static Connection connectAsApp() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "openbench_app", "openbench_app");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
