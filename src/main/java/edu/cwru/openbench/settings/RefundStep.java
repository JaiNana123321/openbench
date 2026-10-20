package edu.cwru.openbench.settings;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** One step of a {@link RefundSchedule}. JSON: {@code {"minLead": "PT168H", "fraction": 0.75}}. */
public record RefundStep(@JsonFormat(shape = JsonFormat.Shape.STRING) Duration minLead, BigDecimal fraction) {

    public RefundStep {
        Objects.requireNonNull(minLead, "minLead");
        Objects.requireNonNull(fraction, "fraction");
    }
}
