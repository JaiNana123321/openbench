package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import java.util.Objects;

/**
 * One Charge for {@link LedgerService#commitCharges}: the requesting Team, the line, and its locked
 * Quote. The line may be on Hold (its held credits are released in the same step) or not.
 */
public record ChargeSpec(TeamId team, RequestLineId line, Credits amount) {

    public ChargeSpec {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("A Charge cannot be negative.");
        }
    }
}
