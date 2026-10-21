package edu.cwru.openbench.allocation;

/**
 * What {@link Allocator#release} gives back. Demo 1 has only the whole line; some occurrences, an
 * earlier end, fewer units, and a Loan's new last day come with Release in a later increment.
 */
public sealed interface ReleasePart {

    /** Every allocated row of the line. */
    record WholeLine() implements ReleasePart {}

    static ReleasePart wholeLine() {
        return new WholeLine();
    }
}
