package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/escalations (design Table "Endpoints by module", Approvals and escalations): the escalated
 * Requests for the Capacity Planner, who decides each approval with POST /api/approvals/{id}/decision.
 */
@RestController
@RequestMapping("/api/escalations")
class EscalationsController {

    private final RequestLifecycleService lifecycle;

    EscalationsController(RequestLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @GetMapping
    List<EscalatedRequest> escalations(Actor actor) {
        return lifecycle.escalations(actor);
    }
}
