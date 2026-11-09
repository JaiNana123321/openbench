package edu.cwru.openbench.allocation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What {@link Allocator#release} gives back (design Section 4.5): the whole line, some occurrences,
 * an earlier end, or fewer units. The rest of the line keeps its instances and its prices. A Loan's
 * new last day comes with Matched Release in a later increment.
 *
 * <p>A part already started, or one that does not lie inside the line's live allocation, is
 * refused with {@code NOT_ALLOCATED}; a part already started returns no credits, so only what has not
 * started can be given back, except the hours after an earlier end inside a running occurrence.
 */
public sealed interface ReleasePart {

    /** Every allocated occurrence of the line that has not started. */
    record WholeLine() implements ReleasePart {}

    /** The occurrences that start at these instants, on every instance they are on. */
    record Occurrences(List<Instant> starts) implements ReleasePart {

        public Occurrences {
            starts = List.copyOf(starts);
            if (starts.isEmpty()) {
                throw new IllegalArgumentException("Name at least one occurrence to give back.");
            }
            if (starts.stream().distinct().count() != starts.size()) {
                throw new IllegalArgumentException("Name each occurrence once.");
            }
        }
    }

    /** The allocation ends at {@code newEnd}: every hour from then on is given back. */
    record EarlierEnd(Instant newEnd) implements ReleasePart {

        public EarlierEnd {
            Objects.requireNonNull(newEnd, "newEnd");
        }
    }

    /** A Metered line keeps {@code newUnits} units over the rest of its window and gives back the others. */
    record FewerUnits(int newUnits) implements ReleasePart {}

    static ReleasePart wholeLine() {
        return new WholeLine();
    }
}
