package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;

/** A row of {@code refund_step}: one step of a type's Refund Schedule. */
@Entity
@Table(name = "refund_step")
class RefundStepRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long typeId;

    private int minLeadHours;

    private BigDecimal fraction;

    private Long createdBy;

    protected RefundStepRow() {}

    /** The step, whose lead time is whole hours. */
    RefundStepRow(Long typeId, RefundStep step, Long createdBy) {
        this.typeId = typeId;
        this.minLeadHours = Math.toIntExact(step.minLead().toHours());
        this.fraction = step.fraction();
        this.createdBy = createdBy;
    }

    Long typeId() {
        return typeId;
    }

    RefundStep toRefundStep() {
        return new RefundStep(Duration.ofHours(minLeadHours), fraction);
    }
}
