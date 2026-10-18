package edu.cwru.openbench.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A row of {@code resource_attribute_value}: a Resource's value of one attribute, stored as text. */
@Entity
@Table(name = "resource_attribute_value")
@IdClass(ResourceAttributeValueRow.Key.class)
class ResourceAttributeValueRow {

    @Id
    private Long resourceId;

    @Id
    private Long attributeId;

    private String value;

    private Long createdBy;

    protected ResourceAttributeValueRow() {}

    ResourceAttributeValueRow(Long resourceId, Long attributeId, String value, Long createdBy) {
        this.resourceId = resourceId;
        this.attributeId = attributeId;
        this.value = value;
        this.createdBy = createdBy;
    }

    Long resourceId() {
        return resourceId;
    }

    Long attributeId() {
        return attributeId;
    }

    String value() {
        return value;
    }

    void change(String value) {
        this.value = value;
    }

    record Key(Long resourceId, Long attributeId) implements Serializable {}
}
