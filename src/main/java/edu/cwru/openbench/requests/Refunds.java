package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocatedPart;
import edu.cwru.openbench.allocation.Released;
import edu.cwru.openbench.allocation.ReleasedPiece;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.ledger.LineSpec;
import edu.cwru.openbench.ledger.Pricing;
import edu.cwru.openbench.settings.RefundSchedule;
import edu.cwru.openbench.settings.SettingsService;
import edu.cwru.openbench.settings.TypePricing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Prices the pieces of a Release (design Section 4.8, "Release"): each piece's share of the line's
 * locked Quote, priced as the Quote was ({@link Pricing#attributable}), times the Refund Schedule's
 * fraction for the lead time to the start of the piece's occurrence ({@link Pricing#refund}); the
 * Release posts the sum as one Refund. A line without a locked Quote returns nothing.
 */
@Component
class Refunds {

    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.US);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.US);

    private final SettingsService settings;
    private final Clock clock;

    Refunds(SettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    RefundQuote quote(RequestLineRow line, Released released, Instant now) {
        ZoneId zone = clock.getZone();
        Optional<TypePricing> pricing = settings.current().pricingOf(new ResourceTypeId(line.typeId()));
        Credits locked = line.lockedQuote() == null ? Credits.ZERO : new Credits(line.lockedQuote());
        RefundSchedule schedule = pricing.map(TypePricing::refundSchedule).orElse(RefundSchedule.NONE);
        LineSpec whole = new LineSpec(line.amount(), line.occurrences(zone), line.priority(), zone);

        List<RefundQuote.Piece> pieces = new ArrayList<>();
        Credits attributable = Credits.ZERO;
        Credits refund = Credits.ZERO;
        for (ReleasedPiece piece : released.pieces()) {
            Duration lead = Duration.between(now, piece.occurrence().start());
            Credits share = pricing.isEmpty() ? Credits.ZERO : Pricing.attributable(locked, whole,
                    new LineSpec(piece.amount(), List.of(piece.released()), line.priority(), zone), pricing.get());
            Credits back = Pricing.refund(share, schedule, lead);
            long leadHours = Math.floorDiv(lead.toSeconds(), 3600);
            pieces.add(new RefundQuote.Piece(piece.occurrence(), piece.released(), piece.amount(), leadHours,
                    Pricing.refundFraction(schedule, lead), share, back));
            attributable = attributable.plus(share);
            refund = refund.plus(back);
        }
        return new RefundQuote(attributable, refund, pieces);
    }

    /**
     * The line's history note for a Release: the part, each piece with its lead time and fraction of
     * its attributable credits, and the Refund. The event row adds the actor and the time.
     */
    String note(RefundQuote quote, String part) {
        String pieces = quote.pieces().stream()
                .map(piece -> describe(piece.released()) + " x" + piece.amount() + " (lead time "
                        + lead(piece.leadHours()) + ", " + percent(piece.fraction()) + " of " + piece.attributable() + ")")
                .collect(Collectors.joining("; "));
        return "Released " + part + ": " + pieces + ". Refund " + quote.refund() + " credits.";
    }

    /** A window in company time, such as "Wed 25 Nov 2026, 12:00 to 14:00". */
    String describe(TimeWindow window) {
        ZonedDateTime start = window.start().atZone(clock.getZone());
        ZonedDateTime end = window.end().atZone(clock.getZone());
        return MESSAGE_TIME.format(start) + " to "
                + (start.toLocalDate().equals(end.toLocalDate()) ? HOUR.format(end) : MESSAGE_TIME.format(end));
    }

    /** An instant in company time, such as "Wed 25 Nov 2026, 12:00". */
    String describe(Instant instant) {
        return MESSAGE_TIME.format(instant.atZone(clock.getZone()));
    }

    private static String lead(long hours) {
        if (hours < 0) {
            return "none, the occurrence has started";
        }
        return hours / 24 + " d " + hours % 24 + " h";
    }

    private static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * The share of the line's locked Quote attributable to these allocated parts, in full, with no
     * Refund Schedule: what a Bump refunds of a partly Released line (SRS Exclusive.Bump.Effect).
     */
    Credits shareOf(RequestLineRow line, List<AllocatedPart> parts) {
        ZoneId zone = clock.getZone();
        Optional<TypePricing> pricing = settings.current().pricingOf(new ResourceTypeId(line.typeId()));
        if (pricing.isEmpty() || line.lockedQuote() == null) {
            return Credits.ZERO;
        }
        Credits locked = new Credits(line.lockedQuote());
        LineSpec whole = new LineSpec(line.amount(), line.occurrences(zone), line.priority(), zone);
        Credits share = Credits.ZERO;
        for (AllocatedPart part : parts) {
            share = share.plus(Pricing.attributable(locked, whole,
                    new LineSpec(part.amount(), List.of(part.window()), line.priority(), zone), pricing.get()));
        }
        return share;
    }
}
