package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
import java.util.List;

/**
 * GET /api/requests/{id}/draft: a Request with its lines and their Quotes, the sums of the Required
 * and the Optional lines' Quotes, the Team's Available Balance, and the shortfall, which is the
 * Required sum minus the Available Balance when that is positive and 0.00 otherwise. Every figure
 * is computed on the server; the client draws them as returned.
 */
public record DraftView(
        Request request,
        List<RequestLine> lines,
        Credits requiredSum,
        Credits optionalSum,
        Credits availableBalance,
        Credits shortfall) {

    public DraftView {
        lines = List.copyOf(lines);
    }
}
