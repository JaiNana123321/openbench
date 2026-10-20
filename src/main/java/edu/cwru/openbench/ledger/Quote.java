package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import java.util.Objects;

/** The price of one line, from {@link Pricing#quote}; locked on the line at submission. JSON: {@code {"amount": 30.00}}. */
public record Quote(Credits amount) {

    public Quote {
        Objects.requireNonNull(amount, "amount");
    }
}
