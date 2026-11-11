package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Objection Window's end, without Spring (design Section 4.8, "Window, escalation, Release"). */
@DisplayName("Lifecycle.Window.Start: ObjectionWindow")
class ObjectionWindowTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(NEW_YORK).toInstant();
    }

    @Test
    @DisplayName("two working days from Thursday at t end on Monday at t, skipping the weekend")
    void thursdayEndsOnMonday() {
        assertThat(ObjectionWindow.endsAt(at("2026-11-19T14:37:05"), 2, NEW_YORK)).isEqualTo(at("2026-11-23T14:37:05"));
    }

    @Test
    @DisplayName("two working days from Friday at 10:00 end on Tuesday at 10:00 (the design's example)")
    void fridayEndsOnTuesday() {
        assertThat(ObjectionWindow.endsAt(at("2026-11-20T10:00:00"), 2, NEW_YORK)).isEqualTo(at("2026-11-24T10:00:00"));
    }

    @Test
    @DisplayName("from a weekend the first working day is Monday; midweek it is plain days")
    void weekendsAndMidweek() {
        assertThat(ObjectionWindow.endsAt(at("2026-11-21T09:00:00"), 2, NEW_YORK)).isEqualTo(at("2026-11-24T09:00:00"));
        assertThat(ObjectionWindow.endsAt(at("2026-11-17T09:00:00"), 2, NEW_YORK)).isEqualTo(at("2026-11-19T09:00:00"));
        assertThat(ObjectionWindow.endsAt(at("2026-11-17T09:00:00"), 0, NEW_YORK)).isEqualTo(at("2026-11-17T09:00:00"));
    }

    @Test
    @DisplayName("the local time is kept across a daylight-saving change")
    void keepsTheLocalTimeAcrossDaylightSaving() {
        // Clocks go back on Sunday, November 1, 2026.
        assertThat(ObjectionWindow.endsAt(at("2026-10-30T10:00:00"), 2, NEW_YORK)).isEqualTo(at("2026-11-03T10:00:00"));
    }
}
