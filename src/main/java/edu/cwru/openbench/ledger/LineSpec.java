package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.TimeWindow;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * What {@link Pricing#quote} needs of a line: the quantity (instances of an Exclusive type, units of
 * a Metered type), its occurrences (one window, or one per week of a recurring line), whether it is
 * a Priority line, the company time zone in which the Price Bands are read (the injected
 * {@code Clock}'s zone), and the premium of a Metered line's service tier (1 without one). Windows
 * start and end on whole hours.
 */
public record LineSpec(int quantity, List<TimeWindow> occurrences, boolean priority, ZoneId zone,
        BigDecimal tierPremium) {

    public LineSpec {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A line's quantity must be positive.");
        }
        occurrences = List.copyOf(occurrences);
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(tierPremium, "tierPremium");
        if (tierPremium.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("A tier premium is at least 1.");
        }
    }

    /** A line without a service tier. */
    public LineSpec(int quantity, List<TimeWindow> occurrences, boolean priority, ZoneId zone) {
        this(quantity, occurrences, priority, zone, BigDecimal.ONE);
    }
}
