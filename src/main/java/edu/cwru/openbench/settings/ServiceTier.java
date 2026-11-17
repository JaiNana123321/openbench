package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.ServiceTierId;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A service tier of a Metered type (SRS Metered.Tier.Define): a name and a premium of at least 1.0
 * that multiplies the Unit Price of a line marked with it. The premium also ranks the tier: a line
 * Bumps only lines of a strictly lower tier, and a line without a tier ranks below every tier.
 * {@code id} is null in a change that adds the tier. JSON: {@code {"id": 3, "name": "Express", "premium": 1.5}}.
 */
public record ServiceTier(ServiceTierId id, String name, BigDecimal premium) {

    public ServiceTier {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(premium, "premium");
    }
}
