package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.TeamId;

/**
 * One Team's person-days of a Period (SRS Dashboard.Credits): the Monday-to-Friday days of its
 * Loans that fall in the Period, lent to other Teams and borrowed from them.
 */
public record PersonDays(TeamId team, String teamName, PeriodId period, int lent, int borrowed) {}
