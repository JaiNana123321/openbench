package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.MeteredAllocationId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Actual usage of Metered allocations (SRS Metered.Usage.Record; design Section 9, "Actual usage
 * recording and comparison"; issue #24). After a Period ends, a Resource Owner records the units
 * each ALLOCATED Metered allocation of their types actually consumed; the Dashboard compares them
 * with the units requested.
 *
 * <p>A Resource Owner sees and records only allocations of a type of which they own at least one
 * in-service Resource; any other allocation is not found. Usage may be recorded once the Period
 * that contains the allocation (the one containing its last hour) has ended by the injected Clock,
 * and again later: the last value wins, with who recorded it and when.
 *
 * <p>Usage has no Ledger effect: nothing here posts a Transaction or touches a Quote, a Charge, or
 * a Balance. The hourly check of the pool and the Quota keeps reading {@code units}, never
 * {@code used_units}. Recording writes only the usage columns and takes no type lock, since it
 * changes no capacity.
 */
@Service
@Transactional(readOnly = true)
public class UsageService {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private final MeteredUsageRepository rows;
    private final InventoryQueries inventory;
    private final MemberService members;
    private final SettingsService settings;
    private final Clock clock;

    UsageService(MeteredUsageRepository rows, InventoryQueries inventory, MemberService members,
            SettingsService settings, Clock clock) {
        this.rows = rows;
        this.inventory = inventory;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * GET /api/usage?periodId=: the ALLOCATED Metered allocations of the caller's types whose window
     * overlaps the Period, by start, each with its usage once recorded.
     *
     * @throws NotFoundException for an unknown Period
     */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    public List<MeteredUsage> usage(Actor actor, PeriodId periodId) {
        Period period = settings.period(periodId);
        Set<ResourceTypeId> owned = inventory.typesOwnedBy(actor.memberId());
        if (owned.isEmpty()) {
            return List.of();
        }
        ZoneId zone = clock.getZone();
        Instant from = period.startsOn().atStartOfDay(zone).toInstant();
        Instant to = period.endsOn().plusDays(1).atStartOfDay(zone).toInstant();
        Views views = new Views();
        return rows.allocatedOverlapping(owned.stream().map(ResourceTypeId::value).toList(), from, to).stream()
                .map(views::of)
                .toList();
    }

    /**
     * PUT /api/usage/{id}: records the units the allocation actually consumed, replacing any value
     * recorded before, and returns the allocation with its usage.
     *
     * @throws NotFoundException for an unknown allocation or one of a type the caller owns no
     *     in-service Resource of
     * @throws RuleViolation {@code WRONG_STATE} when the allocation is not ALLOCATED or the Period
     *     containing it has not ended; {@code VALIDATION_FAILED} for fewer than 0 or more than its units
     */
    @PreAuthorize("hasAuthority('RESOURCE_OWNER')")
    @Transactional
    public MeteredUsage recordUsage(Actor actor, MeteredAllocationId id, int usedUnits) {
        MeteredAllocationRow row = rows.findById(id.value())
                .filter(found -> inventory.typesOwnedBy(actor.memberId()).contains(new ResourceTypeId(found.typeId())))
                .orElseThrow(() -> new NotFoundException("Metered allocation " + id.value() + " is not found."));
        if (row.state() != RowState.ALLOCATED) {
            throw new RuleViolation("WRONG_STATE", "Usage is recorded only for an allocated Metered allocation; this one is "
                    + row.state().name().toLowerCase() + ".");
        }
        if (!periodEnded(row, settings.periods())) {
            throw new RuleViolation("WRONG_STATE",
                    "Usage can be recorded only once the Period containing the allocation has ended.");
        }
        if (usedUnits < 0 || usedUnits > row.units()) {
            throw new RuleViolation("VALIDATION_FAILED",
                    "Units used must be from 0 to the " + row.units() + " units allocated.");
        }
        rows.recordUsage(row.id(), usedUnits, clock.instant(), actor.memberId().value());
        return new Views().of(rows.findById(row.id()).orElseThrow());
    }

    /**
     * Whether the Period containing the allocation's last hour has ended before today; without such a
     * Period, whether that hour's day has passed.
     */
    private boolean periodEnded(MeteredAllocationRow row, List<Period> periods) {
        ZoneId zone = clock.getZone();
        LocalDate lastDay = LocalDate.ofInstant(row.window().end().minusNanos(1), zone);
        LocalDate endsOn = periods.stream().filter(period -> period.contains(lastDay)).findFirst()
                .map(Period::endsOn).orElse(lastDay);
        return endsOn.isBefore(LocalDate.now(clock));
    }

    /** Builds the views of one call, looking up each Team, recorder, and type name once. */
    private final class Views {

        private final List<Period> periods = settings.periods();
        private final Map<Long, TeamSummary> teams = new HashMap<>();
        private final Map<Long, String> names = new HashMap<>();
        private final Map<Long, String> typeNames = new HashMap<>();

        MeteredUsage of(MeteredAllocationRow row) {
            BigDecimal hours = BigDecimal.valueOf(row.window().duration().getSeconds())
                    .divide(SECONDS_PER_HOUR, 6, RoundingMode.HALF_UP);
            BigDecimal requested = BigDecimal.valueOf(row.units()).multiply(hours).setScale(2, RoundingMode.HALF_UP);
            return new MeteredUsage(
                    new MeteredAllocationId(row.id()),
                    teams.computeIfAbsent(row.teamId(), team -> members.team(new TeamId(team))),
                    new RequestId(row.requestId()),
                    new ResourceTypeId(row.typeId()),
                    typeNames.computeIfAbsent(row.typeId(), type -> inventory.type(new ResourceTypeId(type)).name()),
                    row.units(),
                    row.window(),
                    requested,
                    row.usedUnits(),
                    row.usageRecordedAt(),
                    row.usageRecordedBy() == null ? null
                            : names.computeIfAbsent(row.usageRecordedBy(),
                                    member -> members.summary(new MemberId(member)).name()),
                    row.state() == RowState.ALLOCATED && periodEnded(row, periods));
        }
    }
}
