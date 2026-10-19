package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Ledger endpoints (design Table "Endpoints by module"). */
@RestController
@RequestMapping("/api/ledger")
class LedgerController {

    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** The caller's Team's Account; a Capacity Planner may name another Team. */
    @GetMapping("/account")
    AccountView account(Actor actor, @RequestParam(required = false) TeamId teamId) {
        return ledger.accountFor(actor, teamId);
    }

    @GetMapping("/statements/{teamId}/{periodId}")
    Statement statement(Actor actor, @PathVariable TeamId teamId, @PathVariable PeriodId periodId) {
        return ledger.statement(actor, teamId, periodId);
    }

    @PostMapping("/grants")
    AccountView grant(Actor actor, @Valid @RequestBody NewGrant grant) {
        return ledger.grantMidPeriod(actor, grant.teamId(), grant.amount(), grant.reason());
    }

    /** The body of POST /api/ledger/grants: {@code {"teamId": 2, "amount": 200.00, "reason": "..."}}. */
    record NewGrant(@NotNull TeamId teamId, @NotNull Credits amount, String reason) {}
}
