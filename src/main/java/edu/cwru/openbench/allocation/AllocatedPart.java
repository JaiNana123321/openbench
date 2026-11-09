package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.TimeWindow;

/**
 * One occurrence of a line as it is allocated now: its window, shortened by an earlier end, and its
 * instances (Exclusive) or units (Metered), fewer after a Release of units. JSON:
 * {@code {"window": {"start": ..., "end": ...}, "amount": 4}}.
 */
public record AllocatedPart(TimeWindow window, int amount) {}
