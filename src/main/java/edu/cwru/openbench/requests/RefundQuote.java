package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.TimeWindow;
import java.math.BigDecimal;
import java.util.List;

/**
 * The Refund of a Release (Release.Refund), or of a Release previewed: for each released piece, the
 * part of the locked Quote attributable to it and the fraction of the Refund Schedule for its lead
 * time, which runs to the start of the piece's occurrence; and the sums. {@code refund} is what the
 * Release posts as one Refund. JSON: {@code fraction} is a number such as {@code 0.75} and
 * {@code leadHours} the whole hours of the lead time, rounded down, so negative once the
 * occurrence has started.
 */
public record RefundQuote(Credits attributable, Credits refund, List<Piece> pieces) {

    public RefundQuote {
        pieces = List.copyOf(pieces);
    }

    /**
     * One released piece: the hours {@code released} of the occurrence {@code occurrence}, for
     * {@code amount} instances or units.
     */
    public record Piece(
            TimeWindow occurrence,
            TimeWindow released,
            int amount,
            long leadHours,
            BigDecimal fraction,
            Credits attributable,
            Credits refund) {}
}
