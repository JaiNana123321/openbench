package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.TimeWindow;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * What {@link Pricing#quote} needs of a line: the quantity (instances of an Exclusive type, units of
 * a Metered type), its occurrences (one window, or one per week of a recurring line), whether it is
 * a Priority line, and the company time zone in which the Price Bands are read (the injected
 * {@code Clock}'s zone). Windows start and end on whole hours.
 */
public record LineSpec(int quantity, List<TimeWindow> occurrences, boolean priority, ZoneId zone) {

    public LineSpec {
        if (quantity <= 0) {
            throw new IllegalArgumentException("A line's quantity must be positive.");
        }
        occurrences = List.copyOf(occurrences);
        Objects.requireNonNull(zone, "zone");
    }
}
