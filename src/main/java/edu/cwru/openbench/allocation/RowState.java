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
    /** The row went to a Priority line (Exclusive) or a higher-tier line (Metered). */
    BUMPED,
    /** The Hold was dropped before allocation. */
    EXPIRED;

    static final Set<RowState> LIVE = Set.of(HELD, ALLOCATED);
}
