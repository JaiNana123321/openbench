package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.members.TeamSummary;
import java.util.Objects;

/**
 * One part of a Resource's timeline (SRS Inventory.View.Holder): a half-open window that is free, on
 * Hold, or allocated, with the holding Team and Request and, for a Priority line, its mark. A free
 * part has no holder. A part held or allocated outside the caller's scope comes without its holder:
 * {@code team} and {@code request} null and {@code priority} false (design Table "What each role
 * sees"). JSON: {@code {"window": {"start", "end"}, "state": "HELD", "team": {"id", "name"},
 * "request": 42, "priority": false}}.
 */
public record Holding(TimeWindow window, HoldingState state, TeamSummary team, RequestId request, boolean priority) {

    public Holding {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(state, "state");
        if (state == HoldingState.FREE && (team != null || request != null || priority)) {
            throw new IllegalArgumentException("A free part has no holder.");
        }
    }

    static Holding free(TimeWindow window) {
        return new Holding(window, HoldingState.FREE, null, null, false);
    }

    /** The same part with its holder left out, for a caller who may not see it. */
    Holding withoutHolder() {
        return new Holding(window, state, null, null, false);
    }
}
