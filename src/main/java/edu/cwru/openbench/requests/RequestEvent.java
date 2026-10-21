package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import java.time.Instant;

/**
 * One entry of a Request's history: a change of the Request ({@code line} null) or of one line,
 * from {@code fromState} (null when the Request was created) to {@code toState}. {@code actor} and
 * {@code actorName} are null for the system. {@code note} says why, such as a line's misfit, which
 * starts with its reason code: {@code "QUOTA_EXCEEDED: ..."}.
 */
public record RequestEvent(
        Instant at,
        RequestLineId line,
        String fromState,
        String toState,
        MemberId actor,
        String actorName,
        String note) {}
