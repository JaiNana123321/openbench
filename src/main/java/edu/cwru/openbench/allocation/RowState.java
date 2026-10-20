package edu.cwru.openbench.allocation;

import java.util.Set;

/**
 * The state of an {@code exclusive_occurrence} or {@code metered_allocation} row. HELD and ALLOCATED
 * rows are live and block capacity alike; the others stay as history and block nothing.
 */
enum RowState {
    HELD,
    ALLOCATED,
    RELEASED,
    /** Exclusive only: the occurrence went to a Priority line. */
    BUMPED,
    /** The Hold was dropped before allocation. */
    EXPIRED;

    static final Set<RowState> LIVE = Set.of(HELD, ALLOCATED);
}
