package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.Loan;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.LoanId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/loans/{id}/end (design Table "Endpoints by module", Requests): the borrowing Manager ends
 * a Loan early. It answers 200 with the Loan as shortened; it runs once more after a deadlock or lock
 * timeout, and a second failure answers 503.
 */
@RestController
@RequestMapping("/api/loans")
class LoansController {

    private final RequestLifecycleService lifecycle;

    LoansController(RequestLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/{id}/end")
    Loan end(Actor actor, @PathVariable LoanId id, @Valid @RequestBody EndLoan body) {
        return ConcurrencyRetry.once(() -> lifecycle.endLoanEarly(actor, id, body.newEnd()));
    }

    /** The body of POST .../end: {@code {"newEnd": "2026-11-25"}}, the Loan's new last day. */
    record EndLoan(@NotNull LocalDate newEnd) {}
}
