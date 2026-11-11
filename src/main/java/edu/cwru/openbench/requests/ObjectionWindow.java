package edu.cwru.openbench.requests;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The end of an Objection Window, pure (design Section 4.8, "Window, escalation, Release"): the
 * submission instant plus {@code workingDays} Monday-to-Friday days at the same local time in the
 * company zone. Submitted on Thursday at t with two working days, it ends on Monday at t; on Friday
 * at 10:00, on Tuesday at 10:00.
 */
final class ObjectionWindow {

    private ObjectionWindow() {}

    static Instant endsAt(Instant submittedAt, int workingDays, ZoneId zone) {
        ZonedDateTime end = submittedAt.atZone(zone);
        int added = 0;
        while (added < workingDays) {
            end = end.plusDays(1);
            if (end.getDayOfWeek() != DayOfWeek.SATURDAY && end.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return end.toInstant();
    }
}
