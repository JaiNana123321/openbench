package edu.cwru.openbench.common;

/**
 * A role, granted to a signed-in Member as the Spring Security authority of the same name (no
 * {@code ROLE_} prefix), so services check {@code @PreAuthorize("hasAuthority('MANAGER')")}. Every
 * signed-in Member holds {@code MEMBER}; the others are stored in {@code member_role}.
 */
public enum Role {
    MEMBER,
    MANAGER,
    RESOURCE_OWNER,
    PLANNER,
    ADMIN
}
