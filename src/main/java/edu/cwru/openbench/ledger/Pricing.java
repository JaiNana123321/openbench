package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.settings.PriceBand;
import edu.cwru.openbench.settings.RefundSchedule;
import edu.cwru.openbench.settings.RefundStep;
import edu.cwru.openbench.settings.TypePricing;
import java.math.BigDecimal;
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
     * of the Price Band containing that hour (1.0 if none), times the premium for a Priority line;
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
        List<RefundStep> steps = schedule.steps();
        for (RefundStep step : steps) {
            if (step.fraction().signum() < 0 || step.fraction().compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("A refund step must return at least 0% and less than 100%.");
            }
        }
        if (leadTime.isNegative() || leadTime.isZero()) {
            return Credits.ZERO;
        }
        BigDecimal fraction = BigDecimal.ZERO;
        for (RefundStep step : steps) {
            if (step.minLead().compareTo(leadTime) <= 0) {
                fraction = step.fraction();
            }
        }
        return Credits.roundedDown(charge.amount().multiply(fraction));
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
