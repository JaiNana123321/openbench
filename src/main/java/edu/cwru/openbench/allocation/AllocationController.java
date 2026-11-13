package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints Allocation serves under other modules' nouns (design Section 7): a Member's Loans
 * under {@code /members}, since Allocation owns Loans and Members may not depend on it. In member
 * paths {@code me} stands for the caller's own id.
 */
@RestController
@RequestMapping("/api")
class AllocationController {

    private final LoanService loans;

    AllocationController(LoanService loans) {
        this.loans = loans;
    }

    @GetMapping("/members/me/loans")
    List<Loan> myLoans(Actor actor) {
        return loans.loansOf(actor, actor.memberId());
    }

    @GetMapping("/members/{id}/loans")
    List<Loan> loans(Actor actor, @PathVariable MemberId id) {
        return loans.loansOf(actor, id);
    }
}
