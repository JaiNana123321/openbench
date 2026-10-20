package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.TeamId;
import java.util.Objects;

/**
 * A Team's Account: granted, earned, and held credits, the Balance (granted plus earned), and the
 * Available Balance (Balance minus held). JSON:
 * {@code {"team": 1, "granted": 5000.00, "earned": 0.00, "held": 30.00, "balance": 5000.00, "available": 4970.00}}.
 */
public record AccountView(TeamId team, Credits granted, Credits earned, Credits held, Credits balance, Credits available) {

    public AccountView {
        Objects.requireNonNull(team, "team");
    }

    static AccountView of(TeamId team, Credits granted, Credits earned, Credits held) {
        Credits balance = granted.plus(earned);
        return new AccountView(team, granted, earned, held, balance, balance.minus(held));
    }
}
