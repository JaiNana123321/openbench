package edu.cwru.openbench.requests;

import java.util.List;

/**
 * GET /api/requests/{id}: a Request with its lines as they stand after submission and its history,
 * oldest first. A Draft's lines show their current Quotes, a submitted Request's the locked ones.
 */
public record RequestDetail(Request request, List<LineDetail> lines, List<RequestEvent> history) {

    public RequestDetail {
        lines = List.copyOf(lines);
        history = List.copyOf(history);
    }
}
