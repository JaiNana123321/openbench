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

    private Long createdBy;

    protected TypePricingRow() {}

    /** A type's first price, set by the Capacity Planner {@code createdBy}; it has no bump cutoff yet. */
    TypePricingRow(Long typeId, BigDecimal unitPrice, Long createdBy) {
        this.typeId = typeId;
        this.unitPrice = unitPrice;
        this.createdBy = createdBy;
    }

    Long typeId() {
        return typeId;
    }

    BigDecimal unitPrice() {
        return unitPrice;
    }

    Integer bumpCutoffHours() {
        return bumpCutoffHours;
    }

    void changeUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    void changeBumpCutoffHours(int hours) {
        this.bumpCutoffHours = hours;
    }

    /** The demand surcharge rate; 0 when the surcharge is off. */
    private BigDecimal surchargeRate = BigDecimal.ZERO;

    BigDecimal surchargeRate() {
        return surchargeRate;
    }

    void changeSurchargeRate(BigDecimal rate) {
        this.surchargeRate = rate;
    }
}
