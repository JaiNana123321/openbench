package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.AttributeId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/**
 * A row of {@code resource_type_attribute}: one named, typed attribute of a Resource Type.
 * {@code allowedValues} lists the choices of a LIST attribute and is null for every other kind.
 */
@Entity
@Table(name = "resource_type_attribute")
class ResourceTypeAttributeRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long typeId;

    private String name;

    @Enumerated(EnumType.STRING)
    private AttributeKind kind;

    private String[] allowedValues;

    private Long createdBy;

    protected ResourceTypeAttributeRow() {}

    ResourceTypeAttributeRow(Long typeId, String name, AttributeKind kind, List<String> allowedValues, Long createdBy) {
        this.typeId = typeId;
        this.createdBy = createdBy;
        change(name, kind, allowedValues);
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

    AttributeKind kind() {
        return kind;
    }

    /** The choices of a LIST attribute in their defined order; empty for every other kind. */
    List<String> allowedValues() {
        return allowedValues == null ? List.of() : List.of(allowedValues);
    }

    /** Whether a trimmed, non-blank value fits this attribute's kind. */
    boolean accepts(String value) {
        return kind.accepts(value, allowedValues());
    }

    void change(String name, AttributeKind kind, List<String> allowedValues) {
        this.name = name;
        this.kind = kind;
        this.allowedValues = kind == AttributeKind.LIST ? allowedValues.toArray(String[]::new) : null;
    }

    Attribute toAttribute() {
        return new Attribute(new AttributeId(id), name, kind, kind == AttributeKind.LIST ? allowedValues() : null);
    }
}
