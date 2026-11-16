package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.SuggestionId;
import java.time.Instant;

/** The original ask and its recorded decision; the proposal never follows later line edits. */
public record Suggestion(SuggestionId id, MemberId memberId, String memberName,
        MemberId managerId, String managerName, String typeName, NewLine proposal, String note,
        String state, RequestLineId acceptedLineId, MemberId decidedBy, String decidedByName,
        Instant decidedAt, String reason, Instant createdAt) {}
