package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.dashboard.DashboardViews.CreditsRow;
import edu.cwru.openbench.dashboard.DashboardViews.TeamScope;
import edu.cwru.openbench.dashboard.DashboardViews.TypeHours;
import edu.cwru.openbench.dashboard.DashboardViews.TypeScope;
import edu.cwru.openbench.dashboard.DashboardViews.UsageScope;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The showback dashboard (design Section 4.7, Dashboard; SRS Dashboard.Utilization and
 * Dashboard.Credits), read through the views of V170. Read-only: every method runs in a
 * {@code READ ONLY} transaction and takes no lock.
 *
 * <p>Scope follows the design's table, and several roles give the union. Utilization, idle
 * capacity, and waiting lines: a Manager or the Capacity Planner sees every type, a Resource Owner
 * the types of the Resources they own. Credits and person-days: the Capacity Planner sees every
 * Team, a Manager their own; a Resource Owner none (refused by role). A plain Member sees nothing.
 * The scope is a condition of each query's {@code WHERE} clause, never a filter on its results.
 *
 * <p>Time comes from the injected Clock. A Period's window runs from its first day's midnight to the
 * midnight after its last, in the company zone; available hours count 24 for each of its days.
 */
@Service
@Transactional(readOnly = true)
public class DashboardQueries {

    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DashboardViews views;
    private final SettingsService settings;
    private final InventoryQueries inventory;
    private final Clock clock;

    DashboardQueries(DashboardViews views, SettingsService settings, InventoryQueries inventory, Clock clock) {
        this.views = views;
        this.settings = settings;
        this.inventory = inventory;
        this.clock = clock;
    }

    /**
     * GET /api/dashboard/utilization: each type in scope over the Period (the current one if
     * {@code null}), or only the named type.
     *
     * @throws NotFoundException for an unknown Period or type, or a type outside the caller's scope
     * @throws RuleViolation {@code CLASS_MISMATCH} for a Matched type
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'RESOURCE_OWNER', 'PLANNER')")
    public List<TypeUtilization> utilization(Actor actor, PeriodId periodId, ResourceTypeId type) {
        Period period = periodId == null ? settings.currentPeriod() : settings.period(periodId);
        requireMeasured(type);
        long days = ChronoUnit.DAYS.between(period.startsOn(), period.endsOn()) + 1;
        Instant from = period.startsOn().atStartOfDay(clock.getZone()).toInstant();
        Instant to = period.endsOn().plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        List<TypeUtilization> rows = views.typeHours(typeScope(actor), type, from, to).stream()
                .map(row -> {
                    BigDecimal available = available(row, days);
                    BigDecimal allocated = hours(row.allocatedHours());
                    return new TypeUtilization(new ResourceTypeId(row.typeId()), row.name(),
                            AllocationClass.valueOf(row.allocationClass()), period.id(), row.capacity(),
                            available, allocated, available.subtract(allocated), percent(row.allocatedHours(), available));
                })
                .toList();
        return found(rows, type);
    }

    /**
     * GET /api/dashboard/idle: each type in scope over the coming {@code days} days from now,
     * or only the named type.
     *
     * @throws NotFoundException for an unknown type or one outside the caller's scope
     * @throws RuleViolation {@code CLASS_MISMATCH} for a Matched type
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'RESOURCE_OWNER', 'PLANNER')")
    public List<TypeIdle> idleNextDays(Actor actor, int days, ResourceTypeId type) {
        requireMeasured(type);
        Instant from = clock.instant();
        Instant to = from.plus(Duration.ofDays(days));
        List<TypeIdle> rows = views.typeHours(typeScope(actor), type, from, to).stream()
                .map(row -> {
                    BigDecimal available = available(row, days);
                    BigDecimal allocated = hours(row.allocatedHours());
                    return new TypeIdle(new ResourceTypeId(row.typeId()), row.name(),
                            AllocationClass.valueOf(row.allocationClass()), days, from, to, row.capacity(),
                            available, allocated, available.subtract(allocated));
                })
                .toList();
        return found(rows, type);
    }

    /**
     * GET /api/dashboard/waiting: the lines waiting now on each type in scope, or on the named type.
     *
     * @throws NotFoundException for an unknown type or one outside the caller's scope
     * @throws RuleViolation {@code CLASS_MISMATCH} for a Matched type
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'RESOURCE_OWNER', 'PLANNER')")
    public List<TypeWaiting> waitingCount(Actor actor, ResourceTypeId type) {
        requireMeasured(type);
        List<TypeWaiting> rows = views.waiting(typeScope(actor), type).stream()
                .map(row -> new TypeWaiting(new ResourceTypeId(row.typeId()), row.name(),
                        AllocationClass.valueOf(row.allocationClass()), row.waiting()))
                .toList();
        return found(rows, type);
    }

    /**
     * GET /api/dashboard/credits: each Team in scope for the Period (the current one if
     * {@code null}), or only the named Team.
     *
     * @throws NotFoundException for an unknown Period or Team, or a Team outside the caller's scope
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public List<TeamCredits> credits(Actor actor, PeriodId periodId, TeamId team) {
        Period period = periodId == null ? settings.currentPeriod() : settings.period(periodId);
        boolean ended = period.endsOn().isBefore(LocalDate.now(clock));
        List<TeamCredits> rows = views.teamCredits(teamScope(actor), period.id().value(), team).stream()
                .map(row -> credits(row, ended))
                .toList();
        return foundTeam(rows, team);
    }

    /**
     * GET /api/dashboard/person-days: person-days lent and borrowed by each Team in scope in the
     * Period (the current one if {@code null}), or by the named Team only.
     *
     * @throws NotFoundException for an unknown Period or Team, or a Team outside the caller's scope
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public List<PersonDays> personDays(Actor actor, PeriodId periodId, TeamId team) {
        Period period = periodId == null ? settings.currentPeriod() : settings.period(periodId);
        List<PersonDays> rows = views.teamCredits(teamScope(actor), period.id().value(), team).stream()
                .map(row -> new PersonDays(new TeamId(row.teamId()), row.teamName(), new PeriodId(row.periodId()),
                        row.personDaysLent(), row.personDaysBorrowed()))
                .toList();
        return foundTeam(rows, team);
    }

    /**
     * GET /api/dashboard/usage: requested against used unit-hours per Team and Metered type over the
     * Period (the current one if {@code null}), counting only allocations whose usage is recorded,
     * with the number still unrecorded. Scope as for credits, joined by the Resource Owner's: the
     * Capacity Planner sees every Team, a Manager their own Team, a Resource Owner the types of the
     * Resources they own.
     *
     * @throws NotFoundException for an unknown Period
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'RESOURCE_OWNER', 'PLANNER')")
    public List<TeamTypeUsage> usage(Actor actor, PeriodId periodId) {
        Period period = periodId == null ? settings.currentPeriod() : settings.period(periodId);
        Instant from = period.startsOn().atStartOfDay(clock.getZone()).toInstant();
        Instant to = period.endsOn().plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        UsageScope scope = new UsageScope(actor.has(Role.PLANNER), actor.has(Role.MANAGER), actor.teamId(),
                actor.has(Role.RESOURCE_OWNER), actor.memberId());
        return views.usage(scope, from, to).stream()
                .map(row -> new TeamTypeUsage(new TeamId(row.teamId()), row.teamName(),
                        new ResourceTypeId(row.typeId()), row.typeName(), period.id(),
                        hours(row.requestedHours()), hours(row.usedHours()), row.recorded(), row.unrecorded()))
                .toList();
    }

    private static TeamCredits credits(CreditsRow row, boolean ended) {
        Credits balance;
        Credits available;
        if (ended) {
            balance = new Credits(row.closingGranted().add(row.closingEarned()));
            available = balance;
        } else {
            balance = new Credits(row.currentGranted().add(row.currentEarned()));
            available = new Credits(row.currentGranted().add(row.currentEarned()).subtract(row.currentHeld()));
        }
        return new TeamCredits(new TeamId(row.teamId()), row.teamName(), new PeriodId(row.periodId()),
                new Credits(row.grants()), new Credits(row.charges()), new Credits(row.earnings()),
                new Credits(row.refunds()), new Credits(row.expired()), balance, available, ended);
    }

    /** A Manager or the Capacity Planner sees every type; a Resource Owner the types of their Resources. */
    private static TypeScope typeScope(Actor actor) {
        return new TypeScope(actor.has(Role.MANAGER) || actor.has(Role.PLANNER), actor.memberId());
    }

    private static TeamScope teamScope(Actor actor) {
        return new TeamScope(actor.has(Role.PLANNER), actor.has(Role.MANAGER), actor.teamId());
    }

    /** A named type must exist and be Exclusive or Metered. */
    private void requireMeasured(ResourceTypeId type) {
        if (type != null && inventory.type(type).allocationClass() == AllocationClass.MATCHED) {
            throw new RuleViolation("CLASS_MISMATCH",
                    "The dashboard measures Exclusive and Metered types; Resource Type " + type.value() + " is Matched.");
        }
    }

    /** A named type that the scoped query did not return is outside the caller's scope. */
    private static <T> List<T> found(List<T> rows, ResourceTypeId type) {
        if (type != null && rows.isEmpty()) {
            throw new NotFoundException("Resource Type " + type.value() + " is not found.");
        }
        return rows;
    }

    /** A named Team that the scoped query did not return does not exist or is outside the caller's scope. */
    private static <T> List<T> foundTeam(List<T> rows, TeamId team) {
        if (team != null && rows.isEmpty()) {
            throw new NotFoundException("Team " + team.value() + " is not found.");
        }
        return rows;
    }

    private static BigDecimal available(TypeHours row, long days) {
        return hours(BigDecimal.valueOf(row.capacity()).multiply(HOURS_PER_DAY).multiply(BigDecimal.valueOf(days)));
    }

    private static BigDecimal hours(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Allocated over available as a percentage, half-up to 2 decimals; 0 when nothing is available. */
    private static BigDecimal percent(BigDecimal allocated, BigDecimal available) {
        if (available.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return allocated.multiply(HUNDRED).divide(available, 2, RoundingMode.HALF_UP);
    }
}
