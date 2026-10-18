package edu.cwru.openbench.members;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * A row of {@code member}: a Member and their login identity. Package-private, so the password
 * hash never leaves this module; only {@link MemberUserDetailsService} reads it.
 */
@Entity
class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long teamId;

    private String name;

    private String email;

    private String username;

    private String passwordHash;

    private boolean active;

    protected Member() {}

    Long id() {
        return id;
    }

    Long teamId() {
        return teamId;
    }

    String name() {
        return name;
    }

    String username() {
        return username;
    }

    String passwordHash() {
        return passwordHash;
    }

    /** False once an Administrator deactivates the Member: they can no longer sign in. */
    boolean active() {
        return active;
    }
}
