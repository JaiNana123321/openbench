package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.members.TeamSummary;

/**
 * A Person lent by their Team to another for whole days, inclusive at both ends (SRS
 * Matched.Loan.Form). {@code daysLent} counts the Monday-to-Friday days, the person-days an Earning
 * pays for. {@code line} is the borrowing Team's Matched line whose commit formed it.
 * {@code endedEarly} is set once the borrowing Manager ends it before its last day (issue #8).
 */
public record Loan(
        LoanId id,
        RequestLineId line,
        MemberId member,
        String memberName,
        TeamSummary lendingTeam,
        TeamSummary borrowingTeam,
        DateRange days,
        int daysLent,
        boolean endedEarly) {}
