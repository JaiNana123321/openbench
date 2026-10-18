package edu.cwru.openbench.members;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Members endpoints (design Table "Endpoints by module"). A controller binds the request, takes
 * the caller as an {@code Actor} parameter when the service needs one, calls one service operation,
 * and returns its result; role checks and rules live in the service.
 */
@RestController
@RequestMapping("/api")
class MembersController {

    private final MemberService members;

    MembersController(MemberService members) {
        this.members = members;
    }

    @GetMapping("/teams")
    List<TeamSummary> teams() {
        return members.teams();
    }
}
