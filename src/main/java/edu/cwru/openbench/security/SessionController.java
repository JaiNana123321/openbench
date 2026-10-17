package edu.cwru.openbench.security;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.members.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/me: who is signed in. Login and logout are Spring Security's filters ({@link SecurityConfig}). */
@RestController
class SessionController {

    private final MemberService members;

    SessionController(MemberService members) {
        this.members = members;
    }

    @GetMapping("/api/me")
    MeResponse me(Actor actor) {
        return MeResponse.of(members.me(actor));
    }
}
