package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.TeamId;

/**
 * One Team's credits for a Period (SRS Dashboard.Credits): its Transactions of the Period summed by
 * kind, the granted credits that expired when it closed, and the Balance and Available Balance:
 * the Account now while the Period has not ended, and the closing figures of its Statement once it
 * has ({@code ended}; nothing is held in an ended Period, so Available equals Balance).
 */
public record TeamCredits(
        TeamId team,
        String teamName,
        PeriodId period,
        Credits grants,
        Credits charges,
        Credits earnings,
        Credits refunds,
        Credits expired,
        Credits balance,
        Credits available,
        boolean ended) {}
