package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.TeamId;
import java.util.Objects;

/**
 * One Earning for {@link LedgerService#postEarning}: the lending Team, the Loan it pays for, the
 * borrowing Team's Matched line that formed the Loan, the amount (the Day Rate times the Loan's
 * person-days), and the Loan in words for the Statement, such as "Dave Kim to Research, Wed 25 Nov
 * 2026 to Thu 26 Nov 2026, 2 person-days". Ledger knows credits only, so the caller says all of it.
 */
public record EarningSpec(TeamId lendingTeam, LoanId loan, RequestLineId line, Credits amount, String description) {

    public EarningSpec {
        Objects.requireNonNull(lendingTeam, "lendingTeam");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("An Earning cannot be negative; a correction comes with an early end.");
        }
    }
}
