package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.Decision;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.ConcurrencyRetry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approvals endpoints (design Table "Endpoints by module", Approvals and escalations): a lending
 * Manager's open approvals and their decisions. A decision answers 200 with the approval as decided.
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalsController {

    private final RequestLifecycleService lifecycle;

    ApprovalsController(RequestLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping
    List<ApprovalView> approvals(Actor actor) {
        return lifecycle.approvals(actor);
    }

    @PostMapping("/{id}/decision")
    ApprovalView decide(Actor actor, @PathVariable ApprovalId id, @Valid @RequestBody DecisionBody body) {
        return ConcurrencyRetry.once(() -> lifecycle.decideApproval(actor, id, body.decision(), body.reason()));
    }

    /** The body of POST .../decision: {@code {"decision": "APPROVE", "reason": "Dave is free that week"}}. */
    record DecisionBody(@NotNull Decision decision, @Size(max = 1000) String reason) {}
}
