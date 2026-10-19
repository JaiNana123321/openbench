package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A row of {@code planner_setting}: one single-valued setting as text, under one of the keys
 * {@code day_rate}, {@code priority_premium}, {@code objection_window_working_days}, and
 * {@code period_months}. {@link SettingsService} parses the values.
 */
@Entity
@Table(name = "planner_setting")
class PlannerSettingRow {

    @Id
    private String key;

    private String value;

    protected PlannerSettingRow() {}

    String key() {
        return key;
    }

    String value() {
        return value;
    }
}
