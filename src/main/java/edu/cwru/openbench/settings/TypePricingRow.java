package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A row of {@code type_pricing}: the Unit Price and bump cutoff of an Exclusive or Metered type. */
@Entity
@Table(name = "type_pricing")
class TypePricingRow {

    @Id
    private Long typeId;

    private BigDecimal unitPrice;

    /** Null for a Metered type, which has no Bump. */
    private Integer bumpCutoffHours;

    protected TypePricingRow() {}

    Long typeId() {
        return typeId;
    }

    BigDecimal unitPrice() {
        return unitPrice;
    }

    Integer bumpCutoffHours() {
        return bumpCutoffHours;
    }
}
