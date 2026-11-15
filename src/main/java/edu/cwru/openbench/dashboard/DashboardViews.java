package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The SQL over Dashboard's three views (V170), and nothing behind them. Each query applies the
 * caller's scope in its {@code WHERE} clause and takes every time bound as a parameter.
 */
@Component
class DashboardViews {

    /** Allocated hours per type in scope, each Allocated span clipped to [from, to). */
    private static final String TYPE_HOURS = """
            SELECT type_id, type_name, allocation_class, capacity,
                   coalesce(sum(amount * extract(epoch FROM least(ends_at, CAST(:to AS timestamptz))
                                                         - greatest(starts_at, CAST(:from AS timestamptz))) / 3600)
                            FILTER (WHERE starts_at < CAST(:to AS timestamptz) AND ends_at > CAST(:from AS timestamptz)),
                            0) AS allocated_hours
            FROM v_type_hours
            WHERE (retired_at IS NULL OR retired_at > CAST(:from AS timestamptz))
              AND (CAST(:typeId AS bigint) IS NULL OR type_id = CAST(:typeId AS bigint))
              AND (:everyType OR CAST(:me AS bigint) = ANY (owner_ids))
            GROUP BY type_id, type_name, allocation_class, capacity
            ORDER BY type_name, type_id""";

    /** Waiting lines per unretired type in scope. */
    private static final String WAITING = """
            SELECT t.type_id, t.type_name, t.allocation_class, count(w.line_id) AS waiting
            FROM (SELECT DISTINCT type_id, type_name, allocation_class FROM v_type_hours
                  WHERE retired_at IS NULL
                    AND (CAST(:typeId AS bigint) IS NULL OR type_id = CAST(:typeId AS bigint))
                    AND (:everyType OR CAST(:me AS bigint) = ANY (owner_ids))) t
            LEFT JOIN v_waiting w ON w.type_id = t.type_id
            GROUP BY t.type_id, t.type_name, t.allocation_class
            ORDER BY t.type_name, t.type_id""";

    /** Credits and person-days per Team in scope for one Period. */
    private static final String TEAM_CREDITS = """
            SELECT * FROM v_team_credits
            WHERE period_id = :periodId
              AND (CAST(:teamId AS bigint) IS NULL OR team_id = CAST(:teamId AS bigint))
              AND (:everyTeam OR (:manager AND team_id = :ownTeam))
            ORDER BY team_name, team_id""";

    private final NamedParameterJdbcTemplate jdbc;

    DashboardViews(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Which types a caller sees: every type, or those with a Resource the Member owns. */
    record TypeScope(boolean everyType, MemberId me) {}

    /** Which Teams a caller sees: every Team, or their own if they are its Manager. */
    record TeamScope(boolean everyTeam, boolean manager, TeamId ownTeam) {}

    record TypeHours(long typeId, String name, String allocationClass, int capacity, BigDecimal allocatedHours) {}

    record Waiting(long typeId, String name, String allocationClass, int waiting) {}

    record CreditsRow(
            long teamId,
            String teamName,
            long periodId,
            BigDecimal grants,
            BigDecimal charges,
            BigDecimal earnings,
            BigDecimal refunds,
            BigDecimal expired,
            BigDecimal closingGranted,
            BigDecimal closingEarned,
            BigDecimal currentGranted,
            BigDecimal currentEarned,
            BigDecimal currentHeld,
            int personDaysLent,
            int personDaysBorrowed) {}

    List<TypeHours> typeHours(TypeScope scope, ResourceTypeId type, Instant from, Instant to) {
        MapSqlParameterSource parameters = typeParameters(scope, type)
                .addValue("from", utc(from))
                .addValue("to", utc(to));
        return jdbc.query(TYPE_HOURS, parameters, (rs, n) -> new TypeHours(
                rs.getLong("type_id"), rs.getString("type_name"), rs.getString("allocation_class"),
                rs.getInt("capacity"), rs.getBigDecimal("allocated_hours")));
    }

    List<Waiting> waiting(TypeScope scope, ResourceTypeId type) {
        return jdbc.query(WAITING, typeParameters(scope, type), (rs, n) -> new Waiting(
                rs.getLong("type_id"), rs.getString("type_name"), rs.getString("allocation_class"),
                rs.getInt("waiting")));
    }

    List<CreditsRow> teamCredits(TeamScope scope, long periodId, TeamId team) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("teamId", team == null ? null : team.value())
                .addValue("everyTeam", scope.everyTeam())
                .addValue("manager", scope.manager())
                .addValue("ownTeam", scope.ownTeam().value());
        return jdbc.query(TEAM_CREDITS, parameters, (rs, n) -> new CreditsRow(
                rs.getLong("team_id"), rs.getString("team_name"), rs.getLong("period_id"),
                rs.getBigDecimal("grants"), rs.getBigDecimal("charges"), rs.getBigDecimal("earnings"),
                rs.getBigDecimal("refunds"), rs.getBigDecimal("expired"),
                rs.getBigDecimal("closing_granted"), rs.getBigDecimal("closing_earned"),
                rs.getBigDecimal("current_granted"), rs.getBigDecimal("current_earned"),
                rs.getBigDecimal("current_held"),
                rs.getInt("person_days_lent"), rs.getInt("person_days_borrowed")));
    }

    private static MapSqlParameterSource typeParameters(TypeScope scope, ResourceTypeId type) {
        return new MapSqlParameterSource()
                .addValue("typeId", type == null ? null : type.value())
                .addValue("everyType", scope.everyType())
                .addValue("me", scope.me().value());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
