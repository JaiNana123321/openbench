package edu.cwru.openbench.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of credits, always at scale 2 ({@code numeric(12,2)} in the database).
 *
 * <p>The constructor rounds half-up to two decimals, the rule for Quotes ("rounded half-up once per
 * line"), so compute with plain {@link BigDecimal} and wrap the result once. Refunds round down with
 * {@link #roundedDown(BigDecimal)}. JSON: a number with two decimals, such as {@code 30.00}.
 */
public record Credits(@JsonValue BigDecimal amount) implements Comparable<Credits> {

    public static final Credits ZERO = new Credits(BigDecimal.ZERO);

    private static final int SCALE = 2;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Credits {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Credits of(String amount) {
        return new Credits(new BigDecimal(amount));
    }

    public static Credits of(long amount) {
        return new Credits(BigDecimal.valueOf(amount));
    }

    /** Rounds toward zero to two decimals, the rule for Refunds. */
    public static Credits roundedDown(BigDecimal amount) {
        return new Credits(amount.setScale(SCALE, RoundingMode.DOWN));
    }

    public Credits plus(Credits other) {
        return new Credits(amount.add(other.amount));
    }

    public Credits minus(Credits other) {
        return new Credits(amount.subtract(other.amount));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Credits other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
