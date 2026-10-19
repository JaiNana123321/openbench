package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every Capacity Planner setting as one typed snapshot (design Section 4.3), read by the modules
 * that do the arithmetic. {@code pricing} has an entry per priced Exclusive or Metered type;
 * {@code quotas} the Quotas that exist (no entry: no Quota); {@code grants} each Team's planned
 * Grant for the current Period, which is that Period's plan or else the Team's most recent earlier one.
 */
public record PlannerSettings(
        Period currentPeriod,
        int objectionWindowWorkingDays,
        Credits dayRate,
        BigDecimal priorityPremium,
        Map<ResourceTypeId, TypePricing> pricing,
        Map<TeamTypeKey, Integer> quotas,
        Map<TeamId, Credits> grants) {

    public PlannerSettings {
        Objects.requireNonNull(currentPeriod, "currentPeriod");
        Objects.requireNonNull(dayRate, "dayRate");
        Objects.requireNonNull(priorityPremium, "priorityPremium");
        pricing = Map.copyOf(pricing);
        quotas = Map.copyOf(quotas);
        grants = Map.copyOf(grants);
    }

    /** The prices of a type, or empty for a type that has none (a Matched type, or one not yet priced). */
    public Optional<TypePricing> pricingOf(ResourceTypeId type) {
        return Optional.ofNullable(pricing.get(type));
    }

    /** The Team's Quota on a Metered type, or empty when it has none. */
    public Optional<Integer> quotaOf(TeamId team, ResourceTypeId type) {
        return Optional.ofNullable(quotas.get(new TeamTypeKey(team, type)));
    }
}
