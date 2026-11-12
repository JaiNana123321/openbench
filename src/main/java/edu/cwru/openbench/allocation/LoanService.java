package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Member's Loans for their profile (design Section 7: {@code GET /members/{id}/loans}, served by
 * Allocation, which owns Loans). Scoped as the profile is (SRS SE-visibility, Matched.Loan.Visible):
 * the Member, their own Manager, a Capacity Planner, and an Administrator see every Loan of the
 * Member; another Team's Manager sees only the Loans that lent the Member to their Team, or lent from
 * it. Anyone else is told the Member does not exist.
 */
@Service
@Transactional(readOnly = true)
public class LoanService {

    private final AllocationQueries queries;
    private final MemberService members;

    LoanService(AllocationQueries queries, MemberService members) {
        this.queries = queries;
        this.members = members;
    }

    /**
     * GET /api/members/{id}/loans: the Member's Loans in the caller's scope, by first day, with no
     * action to accept or decline them.
     *
     * @throws NotFoundException for an unknown Member, or one whose Loans the caller may not see
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<Loan> loansOf(Actor actor, MemberId member) {
        MemberSummary summary = members.findSummary(member).orElseThrow(() -> notFound(member));
        List<Loan> loans = queries.loansOf(member);
        boolean ownManager = actor.has(Role.MANAGER) && summary.team().id().equals(actor.teamId());
        if (actor.memberId().equals(member) || ownManager || actor.has(Role.PLANNER) || actor.has(Role.ADMIN)) {
            return loans;
        }
        if (actor.has(Role.MANAGER)) {
            List<Loan> involvingTeam = loans.stream()
                    .filter(loan -> loan.borrowingTeam().id().equals(actor.teamId())
                            || loan.lendingTeam().id().equals(actor.teamId()))
                    .toList();
            if (!involvingTeam.isEmpty()) {
                return involvingTeam;
            }
        }
        throw notFound(member);
    }

    private static NotFoundException notFound(MemberId member) {
        return new NotFoundException("Member " + member.value() + " does not exist.");
    }
}
