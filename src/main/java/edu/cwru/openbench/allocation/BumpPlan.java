package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.TeamId;
import java.util.List;
import java.util.Objects;

/**
 * What a Priority line's Bump would do ({@link Bumpable#planBump}): the instances the Priority line
 * gets, by id, and the standard lines it Bumps, by line id, each Bumped whole. A tiered Metered
 * line's plan has no instances, only the lower-tier lines it Bumps. Valid only in the transaction
 * that made it, under the type lock.
 *
 * @param priorityLine the Priority line that does not fit
 * @param instances every instance the Priority line gets, free or cleared
 * @param bumped the lines whose live rows block the cleared instances
 */
public record BumpPlan(LineSnapshot priorityLine, List<ResourceId> instances, List<BumpedLine> bumped) {

    public BumpPlan {
        Objects.requireNonNull(priorityLine, "priorityLine");
        instances = List.copyOf(instances);
        bumped = List.copyOf(bumped);
    }

    /**
     * A line the plan Bumps.
     *
     * @param held whether the line is on Hold (its Request Pending), so it has held credits and no Charge
     */
    public record BumpedLine(RequestLineId line, RequestId request, TeamId team, boolean held) {}
}
