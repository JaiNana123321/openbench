package edu.cwru.openbench.settings;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.ServiceTierId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The prices of one Exclusive or Metered Resource Type: the Unit Price (per instance-hour or
 * unit-hour), its Price Bands, its Refund Schedule, and the bump cutoff. {@code bumpCutoff} is null
 * until the Capacity Planner sets one; a Metered type without one never Bumps. {@code tiers} are a
 * Metered type's service tiers, by premium, lowest first; empty for an Exclusive type.
 * {@code surchargeRate} is the demand surcharge rate (SRS Ledger.Surcharge), 0 when it is off: a
 * Quote is multiplied by 1 + rate x the type's peak fill in the line's window. JSON: the cutoff is
 * an ISO-8601 duration, such as "PT24H".
 */
public record TypePricing(
        Credits unitPrice,
        List<PriceBand> bands,
        RefundSchedule refundSchedule,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Duration bumpCutoff,
        List<ServiceTier> tiers,
        BigDecimal surchargeRate) {

    public TypePricing {
        Objects.requireNonNull(unitPrice, "unitPrice");
        bands = List.copyOf(bands);
        Objects.requireNonNull(refundSchedule, "refundSchedule");
        tiers = List.copyOf(tiers);
        Objects.requireNonNull(surchargeRate, "surchargeRate");
    }

    /** The prices of a type without service tiers or a demand surcharge. */
    public TypePricing(Credits unitPrice, List<PriceBand> bands, RefundSchedule refundSchedule, Duration bumpCutoff) {
        this(unitPrice, bands, refundSchedule, bumpCutoff, List.of(), BigDecimal.ZERO);
    }

    /** The type's tier with this id, or empty when the type has none such. */
    public Optional<ServiceTier> tier(ServiceTierId id) {
        return tiers.stream().filter(tier -> tier.id().equals(id)).findFirst();
    }
}
