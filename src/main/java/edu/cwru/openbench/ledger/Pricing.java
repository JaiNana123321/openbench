package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.settings.PriceBand;
import edu.cwru.openbench.settings.RefundSchedule;
import edu.cwru.openbench.settings.RefundStep;
import edu.cwru.openbench.settings.TypePricing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The Quote formula and the Refund step function (design Section 4.7, SRS Ledger.Price.Formula and
 * Release.Refund). Pure: no Spring, no database, no clock; Requests calls it while drafting and at
 * submission, and the Release path for Refunds.
 */
public final class Pricing {

    private static final Duration HOUR = Duration.ofHours(1);

    private Pricing() {}

    /**
     * The sum, over each hour of each occurrence, of quantity times Unit Price times the multiplier
     * of the Price Band containing that hour (1.0 if none), times the premium for a Priority line,
     * times the premium of a Metered line's service tier (SRS Metered.Tier.Define);
     * rounded half-up to two decimals once, for the whole line. Hours are real elapsed hours, read in
     * the line's zone: the day daylight saving time ends has 25 of them.
     *
     * @throws IllegalArgumentException if a window does not start and end on a whole hour
     */
    public static Quote quote(LineSpec line, TypePricing pricing, BigDecimal priorityPremium) {
        BigDecimal multiplierHours = BigDecimal.ZERO;
        for (TimeWindow occurrence : line.occurrences()) {
            requireWholeHours(occurrence, line.zone());
            for (Instant hour = occurrence.start(); hour.isBefore(occurrence.end()); hour = hour.plus(HOUR)) {
                multiplierHours = multiplierHours.add(multiplierAt(hour, line.zone(), pricing.bands()));
            }
        }
        BigDecimal amount = multiplierHours
                .multiply(BigDecimal.valueOf(line.quantity()))
                .multiply(pricing.unitPrice().amount());
        if (line.priority()) {
            amount = amount.multiply(priorityPremium);
        }
        amount = amount.multiply(line.tierPremium());
        return new Quote(new Credits(amount));
    }

    /**
     * The Refund of a part released {@code leadTime} before it starts: the fraction of the step with
     * the largest minimum lead time not above {@code leadTime}, times the Charge, rounded down. A part
     * already started (no lead time left) or below every step returns nothing; a Refund never reaches
     * the whole Charge.
     *
     * @throws IllegalArgumentException if a step of the schedule returns 100% or more
     */
    public static Credits refund(Credits charge, RefundSchedule schedule, Duration leadTime) {
        return Credits.roundedDown(charge.amount().multiply(refundFraction(schedule, leadTime)));
    }

    /**
     * The fraction {@link #refund} applies for this lead time: the step with the largest minimum lead
     * time not above it, 0 for a part already started or below every step.
     *
     * @throws IllegalArgumentException if a step of the schedule returns 100% or more
     */
    public static BigDecimal refundFraction(RefundSchedule schedule, Duration leadTime) {
        List<RefundStep> steps = schedule.steps();
        for (RefundStep step : steps) {
            if (step.fraction().signum() < 0 || step.fraction().compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("A refund step must return at least 0% and less than 100%.");
            }
        }
        if (leadTime.isNegative() || leadTime.isZero()) {
            return BigDecimal.ZERO;
        }
        BigDecimal fraction = BigDecimal.ZERO;
        for (RefundStep step : steps) {
            if (step.minLead().compareTo(leadTime) <= 0) {
                fraction = step.fraction();
            }
        }
        return fraction;
    }

    /**
     * The part of a line's locked Quote attributable to a released part (design Section 4.8,
     * "Release"), priced as the Quote was: the part's quantity times the band multiplier summed over
     * its hours, as a share of the same sum for the whole line, times the locked Quote. Unit Price and
     * premium multiply both sums alike, so the share is the locked price of the part even after the
     * Unit Price changes. Rounded half-up to two decimals; 0 for a line without hours.
     *
     * @param lockedQuote the whole line's Quote locked at submission
     * @param line the whole line as submitted
     * @param part the released part: its quantity (instances or units) and its hours
     * @throws IllegalArgumentException if a window does not start and end on a whole hour
     */
    public static Credits attributable(Credits lockedQuote, LineSpec line, LineSpec part, TypePricing pricing) {
        BigDecimal whole = weight(line, pricing);
        if (whole.signum() == 0) {
            return Credits.ZERO;
        }
        return new Credits(lockedQuote.amount().multiply(weight(part, pricing))
                .divide(whole, 10, RoundingMode.HALF_UP));
    }

    /** The quantity times the band multiplier, summed over every hour of every occurrence. */
    private static BigDecimal weight(LineSpec line, TypePricing pricing) {
        BigDecimal multiplierHours = BigDecimal.ZERO;
        for (TimeWindow occurrence : line.occurrences()) {
            requireWholeHours(occurrence, line.zone());
            for (Instant hour = occurrence.start(); hour.isBefore(occurrence.end()); hour = hour.plus(HOUR)) {
                multiplierHours = multiplierHours.add(multiplierAt(hour, line.zone(), pricing.bands()));
            }
        }
        return multiplierHours.multiply(BigDecimal.valueOf(line.quantity()));
    }

    private static BigDecimal multiplierAt(Instant hour, ZoneId zone, List<PriceBand> bands) {
        LocalDateTime local = LocalDateTime.ofInstant(hour, zone);
        return bands.stream()
                .filter(band -> band.covers(local))
                .map(PriceBand::multiplier)
                .findFirst()
                .orElse(BigDecimal.ONE);
    }

    private static void requireWholeHours(TimeWindow window, ZoneId zone) {
        if (!isWholeHour(window.start(), zone) || !isWholeHour(window.end(), zone)) {
            throw new IllegalArgumentException("A priced window must start and end on a whole hour.");
        }
    }

    private static boolean isWholeHour(Instant instant, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        return local.getMinute() == 0 && local.getSecond() == 0 && local.getNano() == 0;
    }
}
