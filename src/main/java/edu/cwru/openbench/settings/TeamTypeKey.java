package edu.cwru.openbench.settings;

import com.fasterxml.jackson.annotation.JsonValue;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import java.util.Objects;

/** The key of a Quota: a Team and a Metered Resource Type. JSON (a map key): {@code "2:4"}, Team then type. */
public record TeamTypeKey(TeamId team, ResourceTypeId type) {

    public TeamTypeKey {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(type, "type");
    }

    @JsonValue
    public String asKey() {
        return team.value() + ":" + type.value();
    }
}
