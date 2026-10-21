package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import java.util.List;

/**
 * The capability of a class whose unfit Required lines wait (requirement Exclusive.Waitlist.Join):
 * Exclusive and Metered. The waitlist is ordered Bumped lines first, then Priority lines, then by
 * submission time, then by entry id.
 */
public interface Waitlistable {

    /**
     * Puts a Required line that does not fit on its type's waitlist, at its Request's submission
     * time. Joining again is a no-op: a line has at most one entry.
     *
     * @throws IllegalArgumentException for an Optional line, which never waits
     */
    void join(LineSnapshot line);

    /** Takes the line off the waitlist; a no-op when it is not on it. */
    void leave(LineSnapshot line);

    /** The type's waiting lines whose windows overlap the freed window, in waitlist order. */
    List<WaitingLine> waitingFor(ResourceTypeId type, TimeWindow freed);
}
