package edu.cwru.openbench.members;

import edu.cwru.openbench.common.TeamId;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of POST /api/admin/members: a new Member, their Team, and their initial password, which
 * is hashed with bcrypt and never stored, logged, or returned as typed. bcrypt reads at most 72
 * bytes, so a longer password is refused rather than cut short.
 */
public record NewMember(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull TeamId teamId) {

    /** Leaves the password out, so the record can never write it into a log. */
    @Override
    public String toString() {
        return "NewMember[name=" + name + ", email=" + email + ", username=" + username + ", teamId=" + teamId + "]";
    }
}
