package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.ResourceId;
import java.util.List;

/**
 * One line of a {@link RequestDetail}: the line with its state and locked Quote, the Resources an
 * allocated Exclusive line is on (empty otherwise, and for a Metered line), and, for a line that
 * waits or was Dropped, why: {@code reason} is a stable code ({@code NO_FREE_INSTANCE},
 * {@code POOL_FULL}, {@code QUOTA_EXCEEDED}, {@code INSUFFICIENT_BALANCE}) and {@code reasonMessage}
 * one sentence for the Manager. {@code waitlistPosition} counts from 1 while the line is on its
 * type's waitlist and is null otherwise.
 */
public record LineDetail(
        RequestLine line,
        List<AssignedResource> assignedResources,
        String reason,
        String reasonMessage,
        Integer waitlistPosition) {

    public LineDetail {
        assignedResources = List.copyOf(assignedResources);
    }

    /** A Resource a line is allocated on. JSON: {@code {"id": 21, "name": "Room A"}}. */
    public record AssignedResource(ResourceId id, String name) {}
}
