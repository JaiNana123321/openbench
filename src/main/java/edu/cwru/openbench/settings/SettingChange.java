package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One change of one Capacity Planner setting, the argument of {@link SettingsService#update} (design
 * Section 4.3): one record per kind of setting, each with its key in {@code PUT /api/settings/{key}}.
 * The records carry the values as given; {@code update} validates them.
 */
public sealed interface SettingChange {

    /** The key of the setting in {@code PUT /api/settings/{key}} and in the change log. */
    String key();

    /** {@code period_months}: the length of Periods not yet started, in calendar months. */
    record PeriodLength(int months) implements SettingChange {
        public String key() {
            return "period_months";
        }
    }

    /** {@code day_rate}: the credits per person-day of a Loan. */
    record DayRate(Credits rate) implements SettingChange {
        public DayRate {
            Objects.requireNonNull(rate, "rate");
        }

        public String key() {
            return "day_rate";
        }
    }

    /** {@code priority_premium}: the multiple of the Unit Price a Priority line pays. */
    record PriorityPremium(BigDecimal multiple) implements SettingChange {
        public PriorityPremium {
            Objects.requireNonNull(multiple, "multiple");
        }

        public String key() {
            return "priority_premium";
        }
    }

    /** {@code objection_window_working_days}: the Objection Window, in working days. */
    record ObjectionWindow(int workingDays) implements SettingChange {
        public String key() {
            return "objection_window_working_days";
        }
    }

    /** {@code grant.{teamId}.{periodId}}: the Team's planned Grant from that Period on. */
    record GrantPlan(TeamId team, PeriodId period, Credits amount) implements SettingChange {
        public GrantPlan {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(amount, "amount");
        }

        public String key() {
            return "grant." + team.value() + "." + period.value();
        }
    }

    /** {@code unit_price.{typeId}}: the Unit Price of an Exclusive or Metered type. */
    record UnitPrice(ResourceTypeId type, Credits price) implements SettingChange {
        public UnitPrice {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(price, "price");
        }

        public String key() {
            return "unit_price." + type.value();
        }
    }

    /** {@code price_bands.{typeId}}: every Price Band of the type, replacing the ones it has. */
    record PriceBands(ResourceTypeId type, List<PriceBand> bands) implements SettingChange {
        public PriceBands {
            Objects.requireNonNull(type, "type");
            bands = List.copyOf(bands);
        }

        public String key() {
            return "price_bands." + type.value();
        }
    }

    /** {@code refund_steps.{typeId}}: the type's whole Refund Schedule, replacing the one it has. */
    record Refunds(ResourceTypeId type, RefundSchedule schedule) implements SettingChange {
        public Refunds {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(schedule, "schedule");
        }

        public String key() {
            return "refund_steps." + type.value();
        }
    }

    /** {@code bump_cutoff.{typeId}}: how long before a line of an Exclusive or Metered type starts it may no longer be Bumped. */
    record BumpCutoff(ResourceTypeId type, Duration cutoff) implements SettingChange {
        public BumpCutoff {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(cutoff, "cutoff");
        }

        public String key() {
            return "bump_cutoff." + type.value();
        }
    }

    /** {@code quota.{teamId}.{typeId}}: the most units of a Metered type the Team may hold; empty for none. */
    record Quota(TeamId team, ResourceTypeId type, Optional<Integer> maxUnits) implements SettingChange {
        public Quota {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(maxUnits, "maxUnits");
        }

        public String key() {
            return "quota." + team.value() + "." + type.value();
        }
    }

    /**
     * {@code tiers.{typeId}}: every service tier of a Metered type. A tier with an id keeps it and
     * takes the given name and premium; one without an id is added; a tier of the type left out is
     * removed.
     */
    record Tiers(ResourceTypeId type, List<ServiceTier> tiers) implements SettingChange {
        public Tiers {
            Objects.requireNonNull(type, "type");
            tiers = List.copyOf(tiers);
        }

        public String key() {
            return "tiers." + type.value();
        }
    }
}
