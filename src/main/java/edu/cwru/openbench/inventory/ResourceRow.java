package edu.cwru.openbench.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code resource}: an Exclusive or Metered Resource. An Exclusive Resource has units 1; a
 * Metered one supplies its units to the type's Pool Capacity until it is retired.
 */
@Entity
@Table(name = "resource")
class ResourceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long typeId;

    private String name;

    private Long ownerMemberId;

    private Integer units;

    private Instant retiredAt;

    private Long createdBy;

    protected ResourceRow() {}

    /** A new Resource; whoever adds it becomes its owner. */
    ResourceRow(Long typeId, String name, Long ownerMemberId, int units) {
        this.typeId = typeId;
        this.name = name;
        this.ownerMemberId = ownerMemberId;
        this.units = units;
        this.createdBy = ownerMemberId;
    }

    Long id() {
        return id;
    }

    Long typeId() {
        return typeId;
    }

    String name() {
        return name;
    }

    Long ownerMemberId() {
        return ownerMemberId;
    }

    int units() {
        return units;
    }

    boolean retired() {
        return retiredAt != null;
    }

    void edit(String name, Long ownerMemberId) {
        this.name = name;
        this.ownerMemberId = ownerMemberId;
    }

    void setUnits(int units) {
        this.units = units;
    }

    void retire(Instant at) {
        this.retiredAt = at;
    }
}
