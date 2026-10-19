package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every Capacity Planner setting, read as one typed snapshot (design Section 4.3). Demo 1 has the
 * reads; {@code update} and its change log come in a later increment.
 *
 * <p>Methods a controller calls carry {@code @PreAuthorize}; {@link #current()}, {@link #periods()},
 * and the other unscoped reads are for other modules and carry no role check, so jobs may call them.
 */
@Service
@Transactional(readOnly = true)
public class SettingsService {

    static final String DAY_RATE = "day_rate";
    static final String PRIORITY_PREMIUM = "priority_premium";
    static final String OBJECTION_WINDOW = "objection_window_working_days";

    private final Clock clock;
    private final PeriodRepository periods;
    private final PlannerSettingRepository plannerSettings;
    private final TypePricingRepository typePricing;
    private final PriceBandRepository priceBands;
    private final RefundStepRepository refundSteps;
    private final QuotaRepository quotas;
    private final GrantPlanRepository grantPlans;

    SettingsService(Clock clock, PeriodRepository periods, PlannerSettingRepository plannerSettings,
            TypePricingRepository typePricing, PriceBandRepository priceBands, RefundStepRepository refundSteps,
            QuotaRepository quotas, GrantPlanRepository grantPlans) {
        this.clock = clock;
        this.periods = periods;
        this.plannerSettings = plannerSettings;
        this.typePricing = typePricing;
        this.priceBands = priceBands;
        this.refundSteps = refundSteps;
        this.quotas = quotas;
        this.grantPlans = grantPlans;
    }

    /** GET /api/settings: every setting, for the Capacity Planner. */
    @PreAuthorize("hasAuthority('PLANNER')")
    public PlannerSettings viewSettings() {
        return current();
    }

    /** GET /api/settings/periods: every Period, for a Period selector. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<Period> listPeriods() {
        return periods();
    }

    /**
     * (Unscoped) The settings in force now: the current Period by the injected {@code Clock}, the
     * single-valued settings, every priced type's prices, the Quotas, and each Team's planned Grant.
     *
     * @throws IllegalStateException if no Period contains today or a single-valued setting is missing
     */
    public PlannerSettings current() {
        Period period = currentPeriod();
        Map<String, String> values = plannerSettings.findAll().stream()
                .collect(Collectors.toMap(PlannerSettingRow::key, PlannerSettingRow::value));
        Map<TeamTypeKey, Integer> quotaMap = quotas.findAll().stream().collect(Collectors.toMap(
                quota -> new TeamTypeKey(new TeamId(quota.teamId()), new ResourceTypeId(quota.typeId())),
                QuotaRow::maxUnits));
        Map<TeamId, Credits> grants = grantPlans.findPlannedAsOf(period.startsOn()).stream().collect(Collectors.toMap(
                grant -> new TeamId(grant.getTeamId()), grant -> new Credits(grant.getAmount())));
        return new PlannerSettings(
                period,
                Integer.parseInt(required(values, OBJECTION_WINDOW)),
                new Credits(new BigDecimal(required(values, DAY_RATE))),
                new BigDecimal(required(values, PRIORITY_PREMIUM)),
                pricing(),
                quotaMap,
                grants);
    }

    /**
     * (Unscoped) The Period containing today by the injected {@code Clock}.
     *
     * @throws IllegalStateException if no Period contains today
     */
    public Period currentPeriod() {
        LocalDate today = LocalDate.now(clock);
        PeriodRow row = periods.findContaining(today)
                .orElseThrow(() -> new IllegalStateException("No Period contains " + today + "."));
        return withPrevious(row);
    }

    /** (Unscoped) Every Period, past and planned, by start date. */
    public List<Period> periods() {
        List<Period> all = new ArrayList<>();
        Optional<PeriodRow> previous = Optional.empty();
        for (PeriodRow row : periods.findAllByOrderByStartsOn()) {
            all.add(row.toPeriod(previous));
            previous = Optional.of(row);
        }
        return all;
    }

    /** (Unscoped) One Period. */
    public Period period(PeriodId id) {
        PeriodRow row = periods.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Period " + id.value() + " does not exist."));
        return withPrevious(row);
    }

    private Period withPrevious(PeriodRow row) {
        return row.toPeriod(periods.findFirstByStartsOnBeforeOrderByStartsOnDesc(row.startsOn()));
    }

    private Map<ResourceTypeId, TypePricing> pricing() {
        Map<Long, List<PriceBand>> bands = priceBands.findAll().stream().collect(Collectors.groupingBy(
                PriceBandRow::typeId, Collectors.mapping(PriceBandRow::toPriceBand, Collectors.toList())));
        Map<Long, List<RefundStep>> steps = refundSteps.findAll().stream().collect(Collectors.groupingBy(
                RefundStepRow::typeId, Collectors.mapping(RefundStepRow::toRefundStep, Collectors.toList())));
        Map<ResourceTypeId, TypePricing> pricing = new HashMap<>();
        for (TypePricingRow row : typePricing.findAll()) {
            Duration cutoff = row.bumpCutoffHours() == null ? null : Duration.ofHours(row.bumpCutoffHours());
            pricing.put(new ResourceTypeId(row.typeId()), new TypePricing(
                    new Credits(row.unitPrice()),
                    bands.getOrDefault(row.typeId(), List.of()),
                    new RefundSchedule(steps.getOrDefault(row.typeId(), List.of())),
                    cutoff));
        }
        return pricing;
    }

    private static String required(Map<String, String> values, String key) {
        return Optional.ofNullable(values.get(key))
                .orElseThrow(() -> new IllegalStateException("The planner setting " + key + " is not set."));
    }
}
