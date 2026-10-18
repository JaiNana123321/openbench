package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;

/**
 * A row of {@code member_role}: one stored role of a Member. {@link Role#MEMBER} is never stored.
 * {@code teamId} follows the Member's Team, which keeps at most one Manager per Team in the schema.
 */
@Entity
@IdClass(MemberRole.Key.class)
class MemberRole {

    @Id
    private Long memberId;

    @Id
    @Enumerated(EnumType.STRING)
    private Role role;

    private Long teamId;

    protected MemberRole() {}

    Role role() {
        return role;
    }

    record Key(Long memberId, Role role) implements Serializable {}
}
