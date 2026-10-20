package edu.cwru.openbench.allocation;

import java.time.Instant;

/** Where and when an Exclusive occurrence lies, as read for the least-used pick. */
record OccurrenceSpan(Long resourceId, Instant startsAt, Instant endsAt) {}
