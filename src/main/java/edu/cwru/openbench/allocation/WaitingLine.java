package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;

/** A waitlist entry: a Required line waiting for its type and window. {@code joinedAt} is its Request's submission time. */
public record WaitingLine(
        RequestLineId lineId,
        ResourceTypeId typeId,
        TimeWindow window,
        boolean priority,
        boolean bumped,
        Instant joinedAt) {}
