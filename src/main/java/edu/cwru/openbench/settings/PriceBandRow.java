package edu.cwru.openbench.settings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/** A row of {@code price_band}. {@code weekdays} holds ISO day digits: {@code "12345"} is Monday to Friday. */
@Entity
@Table(name = "price_band")
class PriceBandRow {

    @Id
    private Long id;

    private Long typeId;

    private String weekdays;

    private LocalTime startTime;

    private LocalTime endTime;

    private BigDecimal multiplier;

    protected PriceBandRow() {}

    Long typeId() {
        return typeId;
    }

    PriceBand toPriceBand() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        weekdays.chars().forEach(digit -> days.add(DayOfWeek.of(Character.digit(digit, 10))));
        return new PriceBand(days, startTime, endTime, multiplier);
    }
}
