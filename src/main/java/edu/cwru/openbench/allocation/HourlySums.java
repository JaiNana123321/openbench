package edu.cwru.openbench.allocation;

import edu.cwru.openbench.allocation.HourlyLoad.Hour;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.Resource;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The hourly sums of live units, in SQL (design Section 4.5, MeteredAllocator). Each runs in the
 * caller's transaction on its connection, after the type lock: at READ COMMITTED the statement sees
 * every row committed before it began, including those of the transaction the lock waited for.
 * Pending entity writes of this transaction are flushed first, so the sums see them too.
 */
@Component
class HourlySums {

    /** The design's query: one row per hour of the window, with the type's and the Team's live units. */
    private static final String FOR_WINDOW = """
            SELECT h.hour, coalesce(sum(m.units), 0) AS used,
                   coalesce(sum(m.units) FILTER (WHERE m.team_id = :teamId), 0) AS team_used
            FROM generate_series(CAST(:startsAt AS timestamptz), CAST(:endsAt AS timestamptz) - interval '1 hour',
                                 interval '1 hour') AS h(hour)
            LEFT JOIN metered_allocation m ON m.type_id = :typeId AND m.state IN ('HELD', 'ALLOCATED')
              AND tstzrange(m.starts_at, m.ends_at, '[)') && tstzrange(h.hour, h.hour + interval '1 hour', '[)')
            GROUP BY h.hour ORDER BY h.hour""";

    /**
     * From an instant to the end of the last live row: hours start on the hour and the first is cut
     * at {@code from}, which is then the hour reported. {@code live} selects (units, starts_at,
     * ends_at) of the rows that count.
     */
    private static final String FROM_INSTANT = """
            WITH live AS (%s)
            SELECT greatest(h.hour, CAST(:from AS timestamptz)) AS hour, coalesce(sum(l.units), 0) AS used, 0 AS team_used
            FROM generate_series(date_trunc('hour', CAST(:from AS timestamptz)),
                                 (SELECT max(ends_at) FROM live) - interval '1 microsecond', interval '1 hour') AS h(hour)
            LEFT JOIN live l ON tstzrange(l.starts_at, l.ends_at, '[)')
              && tstzrange(greatest(h.hour, CAST(:from AS timestamptz)), h.hour + interval '1 hour', '[)')
            GROUP BY h.hour ORDER BY h.hour""";

    private static final String LIVE_METERED = """
            SELECT units, starts_at, ends_at FROM metered_allocation
            WHERE type_id = :typeId AND state IN ('HELD', 'ALLOCATED') AND ends_at > :from""";

    private static final String LIVE_EXCLUSIVE = """
            SELECT 1 AS units, starts_at, ends_at FROM exclusive_occurrence
            WHERE resource_id IN (:resourceIds) AND state IN ('HELD', 'ALLOCATED') AND ends_at > :from""";

    /** Per hour of a window, the instances among {@code resourceIds} with a live occurrence in it. */
    private static final String BUSY_INSTANCES = """
            SELECT h.hour, count(DISTINCT o.resource_id) AS used, 0 AS team_used
            FROM generate_series(CAST(:startsAt AS timestamptz), CAST(:endsAt AS timestamptz) - interval '1 hour',
                                 interval '1 hour') AS h(hour)
            LEFT JOIN exclusive_occurrence o ON o.resource_id IN (:resourceIds) AND o.state IN ('HELD', 'ALLOCATED')
              AND tstzrange(o.starts_at, o.ends_at, '[)') && tstzrange(h.hour, h.hour + interval '1 hour', '[)')
            GROUP BY h.hour ORDER BY h.hour""";

    private static final RowMapper<Hour> HOUR = (rs, n) -> new Hour(
            rs.getObject("hour", OffsetDateTime.class).toInstant(), rs.getInt("used"), rs.getInt("team_used"));

    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final InventoryQueries inventory;

    HourlySums(NamedParameterJdbcTemplate jdbc, EntityManager entityManager, InventoryQueries inventory) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.inventory = inventory;
    }

    /** Each hour of a line's window (on whole hours) with the type's and the Team's live Metered units. */
    List<Hour> forWindow(ResourceTypeId type, TeamId team, TimeWindow window) {
        entityManager.flush();
        return jdbc.query(FOR_WINDOW, new MapSqlParameterSource(Map.of(
                "typeId", type.value(),
                "teamId", team.value(),
                "startsAt", utc(window.start()),
                "endsAt", utc(window.end()))), HOUR);
    }

    /**
     * Each hour from {@code from} on with its live units, as {@code AllocationQueries.peakUnitsInUse}
     * counts them: the Metered pool, or the occurrences on one Exclusive instance or on all of them.
     */
    List<Hour> hoursFrom(ResourceTypeId type, Optional<ResourceId> instance, Instant from) {
        return switch (inventory.type(type).allocationClass()) {
            case METERED -> meteredFrom(type, from);
            case EXCLUSIVE -> exclusiveFrom(instance.map(id -> List.of(id.value())).orElseGet(() ->
                    inventory.instancesOf(type).stream().map(Resource::id).map(ResourceId::value).toList()), from);
            case MATCHED -> List.of();
        };
    }

    /** Each hour from {@code from} on with the type's live Metered units; empty when none lie ahead. */
    List<Hour> meteredFrom(ResourceTypeId type, Instant from) {
        entityManager.flush();
        return jdbc.query(FROM_INSTANT.formatted(LIVE_METERED),
                new MapSqlParameterSource(Map.of("typeId", type.value(), "from", utc(from))), HOUR);
    }

    /** Each hour from {@code from} on with the number of live occurrences on these instances. */
    List<Hour> exclusiveFrom(Collection<Long> resourceIds, Instant from) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        entityManager.flush();
        return jdbc.query(FROM_INSTANT.formatted(LIVE_EXCLUSIVE),
                new MapSqlParameterSource(Map.of("resourceIds", resourceIds, "from", utc(from))), HOUR);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * The type's peak fill over these windows (SRS Ledger.Surcharge): the largest share of its
     * capacity live in any hour, at most 1. For a Metered type, the live units over the Pool
     * Capacity; for an Exclusive type, the in-service instances with a live occurrence over the
     * in-service instances. 0 for a capacity of 0 and for a Matched type. Windows are on whole hours.
     */
    BigDecimal peakFill(ResourceTypeId type, List<TimeWindow> windows) {
        int capacity;
        int peak = 0;
        switch (inventory.type(type).allocationClass()) {
            case METERED -> {
                capacity = inventory.poolCapacity(type);
                entityManager.flush();
                for (TimeWindow window : windows) {
                    peak = Math.max(peak, HourlyLoad.peak(jdbc.query(FOR_WINDOW, new MapSqlParameterSource(Map.of(
                            "typeId", type.value(),
                            "teamId", -1L,
                            "startsAt", utc(window.start()),
                            "endsAt", utc(window.end()))), HOUR)));
                }
            }
            case EXCLUSIVE -> {
                List<Long> instances = inventory.instancesOf(type).stream().map(Resource::id).map(ResourceId::value)
                        .toList();
                capacity = instances.size();
                if (capacity > 0) {
                    entityManager.flush();
                    for (TimeWindow window : windows) {
                        peak = Math.max(peak, HourlyLoad.peak(jdbc.query(BUSY_INSTANCES, new MapSqlParameterSource(
                                Map.of("resourceIds", instances, "startsAt", utc(window.start()),
                                        "endsAt", utc(window.end()))), HOUR)));
                    }
                }
            }
            default -> capacity = 0;
        }
        if (capacity <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(peak).divide(BigDecimal.valueOf(capacity), MathContext.DECIMAL64).min(BigDecimal.ONE);
    }
}
