package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.settings.Period;
import java.util.List;

/**
 * A Team's Statement for a Period (SRS Ledger.Statement): the opening granted and earned credits,
 * the totals of each kind, the granted credits that expired when the Period closed (zero while it
 * is open), the closing granted and earned credits, every Transaction of the Period by posting
 * time, and the Account as it stands now.
 */
public record Statement(
        TeamSummary team,
        Period period,
        Figures opening,
        Totals totals,
        Figures closing,
        AccountView account,
        List<TransactionView> transactions) {

    public Statement {
        transactions = List.copyOf(transactions);
    }

    /** Granted and earned credits at an edge of the Period. */
    public record Figures(Credits granted, Credits earned) {}

    /** The Period's Transactions summed by kind, and the granted credits that expired at its end. */
    public record Totals(
            Credits grants,
            Credits charges,
            Credits earnings,
            Credits refunds,
            Credits expired) {}
}
