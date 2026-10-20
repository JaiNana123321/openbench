package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.ResourceId;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The body of PUT /api/requests/{id}/lines/{lineId}: what the line asks for, replaced whole. The
 * Resource Type, and so the Allocation Class, cannot change; the Required mark changes through its
 * own endpoint.
 */
public record LineEdit(
        @NotNull Integer quantity,
        @NotNull Instant start,
        @NotNull Instant end,
        ResourceId namedResourceId) {}
