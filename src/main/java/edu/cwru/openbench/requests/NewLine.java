package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.WeeklyRecurrence;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The body of POST /api/requests/{id}/lines: a Resource Type, the instances (Exclusive) or units
 * (Metered) in {@code quantity}, the window {@code [start, end)} on whole hours, and for an
 * Exclusive line optionally one named Resource of the type. JSON:
 * {@code {"typeId": 2, "quantity": 1, "start": "2026-10-27T14:00:00-04:00", "end": "...", "namedResourceId": 21}}.
 * An Exclusive line may recur weekly: {@code "recurrence": {"occurrences": 3}} repeats the window on
 * the same hours for three weeks in all; absent or null, the line has one occurrence.
 */
public record NewLine(
        @NotNull ResourceTypeId typeId,
        @NotNull Integer quantity,
        @NotNull Instant start,
        @NotNull Instant end,
        ResourceId namedResourceId,
        WeeklyRecurrence recurrence) {}
