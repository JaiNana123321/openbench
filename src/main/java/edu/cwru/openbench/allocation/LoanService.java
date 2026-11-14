package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final MatchedAllocator matched;

    LoanService(AllocationQueries queries, MemberService members, MatchedAllocator matched) {
        this.queries = queries;
        this.members = members;
        this.matched = matched;
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

    // ---------------------------------------------------------------- an early Loan end (issue #8)

    /** (Unscoped) One Loan, or empty when it does not exist; callers check who may see or end it. */
    public Optional<Loan> loan(LoanId id) {
        return matched.loan(id);
    }

    /**
     * (Unscoped, for {@code RequestLifecycleService.endLoanEarly}) Ends a Loan early (SRS
     * Release.Loan.Prorate): its last day becomes {@code newEnd}, its person-days are counted again,
     * and it is marked ended early. The Person is free again from the day after. The Loan keeps its
     * place in {@code loan_one_at_a_time} for the days it lent. Joins the caller's transaction, which
     * holds the Person type's lock; the caller checks who may end it and posts the Earning
     * correction. Returns the Loan as it is now.
     *
     * @throws IllegalArgumentException for an unknown Loan, or a new end before its first day or not
     *     before its last
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Loan endEarly(LoanId id, LocalDate newEnd) {
        return matched.endLoanEarly(id, newEnd);
    }
}
