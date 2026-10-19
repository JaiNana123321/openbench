package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TransactionId;
import java.time.Instant;

/**
 * One Transaction on a Statement. {@code line} is the Request Line it concerns, {@code actor} and
 * {@code actorName} the Member who caused it, and {@code reason} a mid-Period Grant's reason; each
 * is null when there is none (a null actor is the system).
 */
public record TransactionView(
        TransactionId id,
        TransactionKind kind,
        Credits amount,
        Credits grantedPart,
        Credits earnedPart,
        Instant postedAt,
        RequestLineId line,
        MemberId actor,
        String actorName,
        String reason) {}
