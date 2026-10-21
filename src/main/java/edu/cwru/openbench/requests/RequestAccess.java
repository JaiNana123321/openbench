package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ForbiddenException;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import org.springframework.stereotype.Component;

/**
 * The scope rules of a Request, shared by the services of this package. A Manager sees only their
 * own Team's Requests and a Capacity Planner every Team's; anything else is not found (404). Only
 * the Manager of the owning Team changes one; a Planner who can see it but is not its Manager is
 * forbidden (403).
 */
@Component
class RequestAccess {

    private final RequestRepository requests;

    RequestAccess(RequestRepository requests) {
        this.requests = requests;
    }

    /** The Request if the caller may see it: their own Team's as a Manager, any as a Planner. */
    RequestRow visible(Actor actor, RequestId id) {
        return requests.findById(id.value())
                .filter(row -> mayView(actor, row.teamId()))
                .orElseThrow(() -> notFound(id));
    }

    /**
     * The Request, locked ({@code SELECT ... FOR UPDATE}) until the transaction ends, if the caller
     * is its Team's Manager. The scope check reads only the Team id, so the row is loaded after the
     * lock, never a copy read before it. The caller checks the state.
     */
    RequestRow lockForChange(Actor actor, RequestId id) {
        Long team = requests.findTeamIdById(id.value())
                .filter(teamId -> mayView(actor, teamId))
                .orElseThrow(() -> notFound(id));
        requireTeamsManager(actor, team);
        return requests.lockById(id.value()).orElseThrow(() -> notFound(id));
    }

    /** The Request, locked, if the caller is its Team's Manager and it is a Draft ({@code WRONG_STATE}). */
    RequestRow editableDraft(Actor actor, RequestId id) {
        RequestRow request = lockForChange(actor, id);
        if (!request.isDraft()) {
            throw new RuleViolation("WRONG_STATE", "Request " + id.value() + " is "
                    + request.state().name().toLowerCase() + "; only a Draft can be edited.");
        }
        return request;
    }

    /** @throws ForbiddenException unless the caller is the Manager of this Team */
    static void requireTeamsManager(Actor actor, Long teamId) {
        if (!isTeamsManager(actor, teamId)) {
            throw new ForbiddenException("Only the Manager of the Request's Team may change it.");
        }
    }

    /** Whether a Request of this Team is in the caller's scope. */
    static boolean mayView(Actor actor, Long teamId) {
        return isTeamsManager(actor, teamId) || actor.has(Role.PLANNER);
    }

    private static boolean isTeamsManager(Actor actor, Long teamId) {
        return actor.has(Role.MANAGER) && actor.teamId().value() == teamId;
    }

    static NotFoundException notFound(RequestId id) {
        return new NotFoundException("Request " + id.value() + " does not exist.");
    }
}
