package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.members.MemberService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates one {@link SettingChange} and writes its rows (design Section 4.3, the table of
 * changes), inside {@link SettingsService#update}'s transaction. Returns the value before and after
 * as readable text for the change log; null means not set.
 *
 * <p>Every change first takes one transaction-scoped advisory lock for all of Settings, so
 * concurrent changes run one after another and each reads, as its old value, the value the previous
 * one committed (READ COMMITTED reads each statement afresh). Settings change rarely, so one lock
 * costs nothing and also orders a Period-length change against a Grant plan change.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class SettingWriter {

    /** Periods are written this many months ahead of the current one (design Section 4.3). */
    static final int PERIOD_HORIZON_MONTHS = 120;

    /** One above the largest {@code numeric(12,2)} amount. */
    private static final BigDecimal CREDITS_LIMIT = new BigDecimal("10000000000");

    /** One above the largest {@code numeric(6,3)} multiplier. */
    private static final BigDecimal MULTIPLIER_LIMIT = new BigDecimal("1000");

    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final InventoryQueries inventory;
    private final MemberService members;
    private final PeriodRepository periods;
    private final PlannerSettingRepository plannerSettings;
    private final TypePricingRepository typePricing;
    private final PriceBandRepository priceBands;
    private final RefundStepRepository refundSteps;
    private final QuotaRepository quotas;
    private final GrantPlanRepository grantPlans;

    SettingWriter(Clock clock, JdbcTemplate jdbc, InventoryQueries inventory, MemberService members,
            PeriodRepository periods, PlannerSettingRepository plannerSettings, TypePricingRepository typePricing,
            PriceBandRepository priceBands, RefundStepRepository refundSteps, QuotaRepository quotas,
            GrantPlanRepository grantPlans) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.members = members;
        this.periods = periods;
        this.plannerSettings = plannerSettings;
        this.typePricing = typePricing;
        this.priceBands = priceBands;
        this.refundSteps = refundSteps;
        this.quotas = quotas;
        this.grantPlans = grantPlans;
    }

    /** The value of a setting before and after a change, as readable text; null when not set. */
    record Values(String oldValue, String newValue) {}

    /** Waits for every other setting change to commit or roll back. Call it before reading anything. */
    void lock() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('openbench.settings'))", rows -> null);
    }

    /**
     * Validates the change, writes it, and returns the values before and after.
     *
     * @throws RuleViolation with a code of the design's table when a value is refused
     * @throws NotFoundException when the change names a Team, Period, or type that does not exist
     */
    Values apply(SettingChange change, Long actorId) {
        return switch (change) {
            case SettingChange.PeriodLength length -> periodLength(length.months(), actorId);
            case SettingChange.DayRate rate -> {
                atLeast(rate.rate().amount(), BigDecimal.ZERO, "The Day Rate");
                yield single(change.key(), rate.rate().toString());
            }
            case SettingChange.PriorityPremium premium -> {
                atLeast(premium.multiple(), BigDecimal.ONE, "The priority premium");
                yield single(change.key(), premium.multiple().toPlainString());
            }
            case SettingChange.ObjectionWindow window -> {
                if (window.workingDays() < 1) {
                    throw outOfRange("The Objection Window must be at least 1 working day.");
                }
                yield single(change.key(), Integer.toString(window.workingDays()));
            }
            case SettingChange.GrantPlan plan -> grantPlan(plan, actorId);
            case SettingChange.UnitPrice price -> unitPrice(price, actorId);
            case SettingChange.PriceBands bands -> priceBands(bands, actorId);
            case SettingChange.Refunds refunds -> refunds(refunds, actorId);
            case SettingChange.BumpCutoff cutoff -> bumpCutoff(cutoff);
            case SettingChange.Quota quota -> quota(quota, actorId);
        };
    }

    // ---------------------------------------------------------------- single-valued settings

    private Values single(String key, String value) {
        PlannerSettingRow row = plannerSettings.findById(key)
                .orElseThrow(() -> new IllegalStateException("The planner setting " + key + " is not set."));
        String old = row.value();
        row.change(value);
        return new Values(old, value);
    }

    /**
     * The Period length: Periods not yet started are written again at the new length, from the day
     * after the current Period ends to the horizon. A Grant planned from a replaced Period moves to
     * the first new Period starting on or after it, so it still applies from the same day on.
     */
    private Values periodLength(int months, Long actorId) {
        if (months < 1 || months > 12) {
            throw outOfRange("The Period length must be 1 to 12 calendar months.");
        }
        Values values = single("period_months", Integer.toString(months));
        LocalDate today = LocalDate.now(clock);
        PeriodRow current = periods.findContaining(today)
                .orElseThrow(() -> new IllegalStateException("No Period contains " + today + "."));
        LocalDate first = current.endsOn().plusDays(1);
        LocalDate horizon = first.plusMonths(PERIOD_HORIZON_MONTHS);

        List<PeriodRow> replaced = periods.findByStartsOnBetweenOrderByStartsOn(first, horizon.minusDays(1));
        List<Long> replacedIds = replaced.stream().map(PeriodRow::id).toList();
        Map<Long, LocalDate> startOf = replaced.stream().collect(Collectors.toMap(PeriodRow::id, PeriodRow::startsOn));
        List<GrantPlanRow> plans = new ArrayList<>();
        if (!replacedIds.isEmpty()) {
            plans.addAll(grantPlans.findByPeriodIdIn(replacedIds));
            plans.sort(Comparator.comparing(plan -> startOf.get(plan.periodId())));
            grantPlans.deleteByPeriodIds(replacedIds);
            periods.deleteByIds(replacedIds);
        }

        List<PeriodRow> written = new ArrayList<>();
        for (int n = 0; n < PERIOD_HORIZON_MONTHS / months; n++) {
            written.add(periods.save(new PeriodRow(first.plusMonths((long) n * months),
                    first.plusMonths((long) (n + 1) * months).minusDays(1), actorId)));
        }
        Map<GrantPlanRow.Key, GrantPlanRow> moved = new LinkedHashMap<>();
        for (GrantPlanRow plan : plans) {
            LocalDate from = startOf.get(plan.periodId());
            written.stream().filter(period -> !period.startsOn().isBefore(from)).findFirst().ifPresent(period ->
                    moved.put(new GrantPlanRow.Key(plan.teamId(), period.id()),
                            new GrantPlanRow(plan.teamId(), period.id(), plan.amount(), actorId)));
        }
        grantPlans.saveAll(moved.values());
        return values;
    }

    // ---------------------------------------------------------------- Grants and Quotas

    private Values grantPlan(SettingChange.GrantPlan plan, Long actorId) {
        members.team(plan.team());
        PeriodRow period = periods.findById(plan.period().value())
                .orElseThrow(() -> new NotFoundException("Period " + plan.period().value() + " does not exist."));
        if (!period.startsOn().isAfter(LocalDate.now(clock))) {
            throw new RuleViolation("PERIOD_STARTED", "The Period starting " + period.startsOn()
                    + " has started; plan the Grant from a later Period, or post a mid-Period Grant.");
        }
        BigDecimal amount = plan.amount().amount();
        if (amount.signum() < 0 || amount.compareTo(CREDITS_LIMIT) >= 0) {
            throw outOfRange("A planned Grant must be 0.00 credits or more.");
        }
        Long team = plan.team().value();
        String old = grantPlans.findPlannedAsOf(period.startsOn()).stream()
                .filter(planned -> planned.getTeamId().equals(team))
                .map(planned -> new Credits(planned.getAmount()).toString())
                .findFirst().orElse(null);
        grantPlans.findById(new GrantPlanRow.Key(team, period.id())).ifPresentOrElse(
                row -> row.changeAmount(amount),
                () -> grantPlans.save(new GrantPlanRow(team, period.id(), amount, actorId)));
        return new Values(old, plan.amount().toString());
    }

    private Values quota(SettingChange.Quota quota, Long actorId) {
        members.team(quota.team());
        requireClass(quota.type(), "A Quota", AllocationClass.METERED);
        if (quota.maxUnits().isPresent() && quota.maxUnits().get() < 1) {
            throw outOfRange("A Quota must be at least 1 unit; remove it to have none.");
        }
        QuotaRow.Key key = new QuotaRow.Key(quota.team().value(), quota.type().value());
        Optional<QuotaRow> row = quotas.findById(key);
        String old = row.map(found -> Integer.toString(found.maxUnits())).orElse(null);
        if (quota.maxUnits().isEmpty()) {
            row.ifPresent(quotas::delete);
            return new Values(old, null);
        }
        int units = quota.maxUnits().get();
        row.ifPresentOrElse(found -> found.changeMaxUnits(units),
                () -> quotas.save(new QuotaRow(key.teamId(), key.typeId(), units, actorId)));
        return new Values(old, Integer.toString(units));
    }

    // ---------------------------------------------------------------- a type's prices

    private Values unitPrice(SettingChange.UnitPrice price, Long actorId) {
        requireClass(price.type(), "A Unit Price", AllocationClass.EXCLUSIVE, AllocationClass.METERED);
        BigDecimal amount = price.price().amount();
        if (amount.signum() < 0 || amount.compareTo(CREDITS_LIMIT) >= 0) {
            throw outOfRange("A Unit Price must be 0.00 credits or more.");
        }
        Optional<TypePricingRow> row = typePricing.findById(price.type().value());
        String old = row.map(found -> new Credits(found.unitPrice()).toString()).orElse(null);
        row.ifPresentOrElse(found -> found.changeUnitPrice(amount),
                () -> typePricing.save(new TypePricingRow(price.type().value(), amount, actorId)));
        return new Values(old, price.price().toString());
    }

    private Values bumpCutoff(SettingChange.BumpCutoff cutoff) {
        requireClass(cutoff.type(), "A bump cutoff", AllocationClass.EXCLUSIVE);
        Duration value = cutoff.cutoff();
        if (value.isNegative() || !wholeHours(value)) {
            throw outOfRange("A bump cutoff must be a whole number of hours, 0 or more.");
        }
        TypePricingRow row = typePricing.findById(cutoff.type().value())
                .orElseThrow(() -> new RuleViolation("UNIT_PRICE_REQUIRED",
                        "Set the type's Unit Price before its bump cutoff."));
        String old = row.bumpCutoffHours() == null ? null : hoursText(row.bumpCutoffHours());
        int hours = Math.toIntExact(value.toHours());
        row.changeBumpCutoffHours(hours);
        return new Values(old, hoursText(hours));
    }

    private Values priceBands(SettingChange.PriceBands change, Long actorId) {
        requireClass(change.type(), "A Price Band", AllocationClass.EXCLUSIVE, AllocationClass.METERED);
        List<PriceBand> bands = change.bands().stream().map(SettingWriter::validBand).toList();
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                if (overlap(bands.get(i), bands.get(j))) {
                    throw new RuleViolation("BANDS_OVERLAP", "The Price Bands " + bandText(bands.get(i)) + " and "
                            + bandText(bands.get(j)) + " share an hour; Price Bands of a type may not overlap.");
                }
            }
        }
        Long type = change.type().value();
        String old = bandsText(priceBands.findByTypeId(type).stream().map(PriceBandRow::toPriceBand).toList());
        priceBands.deleteByTypeId(type);
        priceBands.saveAll(bands.stream().map(band -> new PriceBandRow(type, band, actorId)).toList());
        return new Values(old, bandsText(bands));
    }

    private Values refunds(SettingChange.Refunds change, Long actorId) {
        requireClass(change.type(), "A Refund Schedule", AllocationClass.EXCLUSIVE, AllocationClass.METERED);
        List<RefundStep> steps = new ArrayList<>();
        Set<Duration> leads = new HashSet<>();
        for (RefundStep step : change.schedule().steps()) {
            BigDecimal fraction = step.fraction().setScale(3, RoundingMode.HALF_UP);
            if (step.fraction().compareTo(BigDecimal.ONE) >= 0 || fraction.compareTo(BigDecimal.ONE) >= 0) {
                throw new RuleViolation("REFUND_STEP_TOO_HIGH", "A Refund Schedule step returns "
                        + percent(step.fraction()) + "; no step may return 100% or more.");
            }
            if (fraction.signum() < 0) {
                throw outOfRange("A Refund Schedule step returns 0% or more.");
            }
            if (step.minLead().isNegative() || !wholeHours(step.minLead())) {
                throw outOfRange("A Refund Schedule step's lead time must be a whole number of hours, 0 or more.");
            }
            if (!leads.add(step.minLead())) {
                throw outOfRange("Two Refund Schedule steps have the lead time of " + hoursText(step.minLead().toHours())
                        + "; give each step its own.");
            }
            steps.add(new RefundStep(step.minLead(), fraction));
        }
        Long type = change.type().value();
        String old = stepsText(refundSteps.findByTypeId(type).stream().map(RefundStepRow::toRefundStep).toList());
        refundSteps.deleteByTypeId(type);
        refundSteps.saveAll(steps.stream().map(step -> new RefundStepRow(type, step, actorId)).toList());
        return new Values(old, stepsText(steps));
    }

    // ---------------------------------------------------------------- rules

    private void requireClass(ResourceTypeId type, String what, AllocationClass... allowed) {
        AllocationClass actual = inventory.type(type).allocationClass();
        if (!EnumSet.of(allowed[0], allowed).contains(actual)) {
            String names = Arrays.stream(allowed).map(SettingWriter::className).collect(Collectors.joining(" or "));
            String article = allowed[0] == AllocationClass.EXCLUSIVE ? "an " : "a ";
            throw new RuleViolation("CLASS_MISMATCH",
                    what + " is set only for " + article + names + " type; this type is " + className(actual) + ".");
        }
    }

    private static String className(AllocationClass allocationClass) {
        String name = allocationClass.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The band with its multiplier at the column's three decimals, or BAND_INVALID. */
    private static PriceBand validBand(PriceBand band) {
        BigDecimal multiplier = band.multiplier().setScale(3, RoundingMode.HALF_UP);
        if (band.weekdays().isEmpty()) {
            throw new RuleViolation("BAND_INVALID", "A Price Band needs at least one weekday.");
        }
        if (!band.end().equals(LocalTime.MIDNIGHT) && !band.start().isBefore(band.end())) {
            throw new RuleViolation("BAND_INVALID", "A Price Band must start before it ends; an end of 00:00 is midnight.");
        }
        if (multiplier.signum() <= 0 || multiplier.compareTo(MULTIPLIER_LIMIT) >= 0) {
            throw new RuleViolation("BAND_INVALID", "A Price Band's multiplier must be more than 0 and less than 1000.");
        }
        return new PriceBand(band.weekdays(), band.start(), band.end(), multiplier);
    }

    /** Whether some hour of some weekday lies in both bands; an end of 00:00 is the midnight ending the day. */
    private static boolean overlap(PriceBand a, PriceBand b) {
        if (a.weekdays().stream().noneMatch(b.weekdays()::contains)) {
            return false;
        }
        return a.start().toSecondOfDay() < endSecond(b) && b.start().toSecondOfDay() < endSecond(a);
    }

    private static int endSecond(PriceBand band) {
        return band.end().equals(LocalTime.MIDNIGHT) ? 24 * 3600 : band.end().toSecondOfDay();
    }

    private static boolean wholeHours(Duration duration) {
        return duration.toSeconds() % 3600 == 0 && duration.getNano() == 0 && duration.toHours() <= Integer.MAX_VALUE;
    }

    private static void atLeast(BigDecimal value, BigDecimal minimum, String what) {
        if (value.compareTo(minimum) < 0) {
            throw outOfRange(what + " must be at least " + minimum.toPlainString() + ".");
        }
    }

    private static RuleViolation outOfRange(String message) {
        return new RuleViolation("VALUE_OUT_OF_RANGE", message);
    }

    // ---------------------------------------------------------------- readable text for the log

    private static String hoursText(long hours) {
        return hours + (hours == 1 ? " hour" : " hours");
    }

    private static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    /** Such as "Mon Tue Wed Thu Fri 09:00-17:00 x1.5; Sat Sun 00:00-24:00 x0.5", or null for none. */
    static String bandsText(List<PriceBand> bands) {
        if (bands.isEmpty()) {
            return null;
        }
        return bands.stream()
                .sorted(Comparator.<PriceBand, DayOfWeek>comparing(band -> band.weekdays().stream().min(Comparator.naturalOrder()).orElseThrow())
                        .thenComparing(PriceBand::start))
                .map(SettingWriter::bandText)
                .collect(Collectors.joining("; "));
    }

    private static String bandText(PriceBand band) {
        String days = band.weekdays().stream().sorted()
                .map(day -> day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .collect(Collectors.joining(" "));
        String end = band.end().equals(LocalTime.MIDNIGHT) ? "24:00" : band.end().toString();
        return days + " " + band.start() + "-" + end + " x" + band.multiplier().stripTrailingZeros().toPlainString();
    }

    /** Such as "50% from 168 hours; 10% from 0 hours", longest lead first, or null for none. */
    static String stepsText(List<RefundStep> steps) {
        if (steps.isEmpty()) {
            return null;
        }
        return steps.stream()
                .sorted(Comparator.comparing(RefundStep::minLead).reversed())
                .map(step -> percent(step.fraction()) + " from " + hoursText(step.minLead().toHours()))
                .collect(Collectors.joining("; "));
    }
}
