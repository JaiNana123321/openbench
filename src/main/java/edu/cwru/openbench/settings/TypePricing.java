package edu.cwru.openbench.settings;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.ServiceTierId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The prices of one Exclusive or Metered Resource Type: the Unit Price (per instance-hour or
 * unit-hour), its Price Bands, its Refund Schedule, and the bump cutoff. {@code bumpCutoff} is null
 * until the Capacity Planner sets one; a Metered type without one never Bumps. {@code tiers} are a
 * Metered type's service tiers, by premium, lowest first; empty for an Exclusive type. JSON: the
 * cutoff is an ISO-8601 duration, such as "PT24H".
 */
public record TypePricing(
        Credits unitPrice,
        List<PriceBand> bands,
        RefundSchedule refundSchedule,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Duration bumpCutoff,
        List<ServiceTier> tiers) {

    public TypePricing {
        Objects.requireNonNull(unitPrice, "unitPrice");
        bands = List.copyOf(bands);
        Objects.requireNonNull(refundSchedule, "refundSchedule");
        tiers = List.copyOf(tiers);
    }

    /** The prices of a type without service tiers. */
    public TypePricing(Credits unitPrice, List<PriceBand> bands, RefundSchedule refundSchedule, Duration bumpCutoff) {
        this(unitPrice, bands, refundSchedule, bumpCutoff, List.of());
    }

    /** The type's tier with this id, or empty when the type has none such. */
    public Optional<ServiceTier> tier(ServiceTierId id) {
        return tiers.stream().filter(tier -> tier.id().equals(id)).findFirst();
    }
}
