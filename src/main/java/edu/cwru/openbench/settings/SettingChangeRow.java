package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code setting_change}: one logged change, written in the same transaction as the change.
 * Insert-only (V7 revokes UPDATE and DELETE), so this entity has no setters. A null old or new value
 * means the setting was not set (no Quota, an unpriced type, no plan).
 */
@Entity
@Table(name = "setting_change")
class SettingChangeRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String key;

    private String oldValue;

    private String newValue;

    private Long actorId;

    private Instant at;

    private Long createdBy;

    protected SettingChangeRow() {}

    SettingChangeRow(String key, String oldValue, String newValue, Long actorId, Instant at) {
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actorId = actorId;
        this.at = at;
        this.createdBy = actorId;
    }

    Long id() {
        return id;
    }

    String key() {
        return key;
    }

    String oldValue() {
        return oldValue;
    }

    String newValue() {
        return newValue;
    }

    Long actorId() {
        return actorId;
    }

    Instant at() {
        return at;
    }
}
