package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.ServiceTierId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** A row of {@code service_tier}: one tier of a Metered type. */
@Entity
@Table(name = "service_tier")
class ServiceTierRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long typeId;

    private String name;

    private BigDecimal premium;

    private Long createdBy;

    protected ServiceTierRow() {}

    ServiceTierRow(Long typeId, String name, BigDecimal premium, Long createdBy) {
        this.typeId = typeId;
        this.name = name;
        this.premium = premium;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    Long typeId() {
        return typeId;
    }

    void change(String name, BigDecimal premium) {
        this.name = name;
        this.premium = premium;
    }

    ServiceTier toServiceTier() {
        return new ServiceTier(new ServiceTierId(id), name, premium);
    }
}
