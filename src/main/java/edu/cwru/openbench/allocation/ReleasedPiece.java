package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.TimeWindow;
import java.util.Objects;

/**
 * One piece of a Release: the hours {@code released} of the occurrence {@code occurrence}, for
 * {@code amount} instances (Exclusive) or units (Metered). The Refund's lead time runs to the
 * occurrence's start, so an earlier end inside a running occurrence returns nothing.
 */
public record ReleasedPiece(TimeWindow occurrence, TimeWindow released, int amount) {

    public ReleasedPiece {
        Objects.requireNonNull(occurrence, "occurrence");
        Objects.requireNonNull(released, "released");
        if (amount < 1) {
            throw new IllegalArgumentException("A released piece gives back at least one instance or unit.");
        }
    }
}
