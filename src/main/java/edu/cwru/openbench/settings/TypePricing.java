package edu.cwru.openbench.settings;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.cwru.openbench.common.Credits;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The prices of one Exclusive or Metered Resource Type: the Unit Price (per instance-hour or
 * unit-hour), its Price Bands, its Refund Schedule, and the bump cutoff. {@code bumpCutoff} is null
 * for a Metered type, which has no Bump. JSON: the cutoff is an ISO-8601 duration, such as "PT24H".
 */
public record TypePricing(
        Credits unitPrice,
        List<PriceBand> bands,
        RefundSchedule refundSchedule,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Duration bumpCutoff) {

    public TypePricing {
        Objects.requireNonNull(unitPrice, "unitPrice");
        bands = List.copyOf(bands);
        Objects.requireNonNull(refundSchedule, "refundSchedule");
    }
}
