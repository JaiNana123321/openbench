package edu.cwru.openbench.jobs;

import edu.cwru.openbench.common.Role;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The system security context a job runs under (the design's Jobs module): every role, so
 * {@code @PreAuthorize} admits it, and no Member, so the services record the system (a null actor)
 * on every row the job causes.
 */
final class SystemContext {

    /** The principal's name in logs; it is not a Member's username. */
    static final String SYSTEM = "system";

    private static final List<GrantedAuthority> EVERY_ROLE = Arrays.stream(Role.values())
            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.name()))
            .toList();

    private SystemContext() {}

    /** Runs the work as the system, then restores the thread's previous security context. */
    static void run(Runnable work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext system = SecurityContextHolder.createEmptyContext();
        system.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(SYSTEM, null, EVERY_ROLE));
        SecurityContextHolder.setContext(system);
        try {
            work.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
