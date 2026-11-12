package edu.cwru.openbench.security;

import edu.cwru.openbench.members.MemberAccessChanged;
import edu.cwru.openbench.members.SignedInMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ends a Member's sessions once an Administrator's change to them commits (design Section 7,
 * "Authentication and Sessions"): a session holds the roles and Team of its sign-in, so after a
 * deactivation, a granted or revoked role, or a move it would act on stale ones. The session is
 * marked expired in Spring Security's session registry, and its next call answers 401
 * {@code UNAUTHENTICATED}; the Member signs in again, unless deactivated.
 *
 * <p>Members publishes {@link MemberAccessChanged} rather than calling this class, because Members
 * sits below {@code security} and may not depend on it.
 */
@Component
class SessionExpiry {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiry.class);

    private final SessionRegistry sessions;

    SessionExpiry(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    /** After the commit, so a change that rolls back leaves the sessions alone. */
    @TransactionalEventListener(fallbackExecution = true)
    void endSessions(MemberAccessChanged changed) {
        int ended = 0;
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof SignedInMember member && member.actor().memberId().equals(changed.member())) {
                for (SessionInformation session : sessions.getAllSessions(principal, false)) {
                    session.expireNow();
                    ended++;
                }
            }
        }
        if (ended > 0) {
            log.info("Ended {} session(s) of Member {} after an Administrator's change", ended,
                    changed.member().value());
        }
    }
}
