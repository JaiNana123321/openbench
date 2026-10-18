package edu.cwru.openbench.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code resource_type}: a Resource Type defined as data. Package-private, so other
 * modules see {@link ResourceType}. The row is also the lock that serializes allocation on its type.
 */
@Entity
@Table(name = "resource_type")
class ResourceTypeRow {

    /** The built-in type of People, seeded by the schema migration and found by this name. */
    static final String PERSON = "Person";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private AllocationClass allocationClass;

    private Instant retiredAt;

    private Long createdBy;

    protected ResourceTypeRow() {}

    ResourceTypeRow(String name, AllocationClass allocationClass, Long createdBy) {
        this.name = name;
        this.allocationClass = allocationClass;
        this.createdBy = createdBy;
    }

    Long id() {
        return id;
    }

    String name() {
        return name;
    }

    AllocationClass allocationClass() {
        return allocationClass;
    }

    boolean retired() {
        return retiredAt != null;
    }

    boolean isPerson() {
        return PERSON.equals(name);
    }

    void change(String name, AllocationClass allocationClass) {
        this.name = name;
        this.allocationClass = allocationClass;
    }

    void retire(Instant at) {
        this.retiredAt = at;
    }
}
