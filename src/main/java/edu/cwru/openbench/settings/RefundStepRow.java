package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;

/** A row of {@code refund_step}: one step of a type's Refund Schedule. */
@Entity
@Table(name = "refund_step")
class RefundStepRow {

    @Id
    private Long id;

    private Long typeId;

    private int minLeadHours;

    private BigDecimal fraction;

    protected RefundStepRow() {}

    Long typeId() {
        return typeId;
    }

    RefundStep toRefundStep() {
        return new RefundStep(Duration.ofHours(minLeadHours), fraction);
    }
}
