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

    private Long createdBy;

    protected Member() {}

    /** A new active Member; {@code passwordHash} is the bcrypt hash, never the password itself. */
    Member(Long teamId, String name, String email, String username, String passwordHash, Long createdBy) {
        this.teamId = teamId;
        this.name = name;
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.active = true;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    Long teamId() {
        return teamId;
    }

    String name() {
        return name;
    }

    String email() {
        return email;
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

    /** The Member can no longer sign in; their rows and history stay. */
    void deactivate() {
        this.active = false;
    }

    /** Moves the Member to another Team; their stored roles follow by the foreign key's cascade. */
    void moveTo(Long teamId) {
        this.teamId = teamId;
    }
}
