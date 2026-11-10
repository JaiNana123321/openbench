package edu.cwru.openbench.common;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A weekly recurrence of an Exclusive line (SRS Draft.Line.Add, Exclusive.Conflict): the line's
 * window is its first occurrence, and the same hours recur every week for {@code occurrences} weeks
 * in all, the first included. JSON: {@code {"occurrences": 3}}; a line without one has a single
 * occurrence, its window. Weeks are counted in the company time zone, so 10:00 stays 10:00 across a
 * daylight-saving change.
 */
public record WeeklyRecurrence(int occurrences) {

    /** The fewest occurrences of a recurrence: one would be no recurrence at all. */
    public static final int MIN_OCCURRENCES = 2;

    /** The most occurrences of a recurrence: one year of weeks. */
    public static final int MAX_OCCURRENCES = 52;

    /**
     * The occurrences of a line whose first occurrence is {@code first}: {@code first} alone without a
     * recurrence, else one window per week, each at the same local hours as the first in the zone.
     *
     * @param recurrence the line's recurrence, or null for a single occurrence
     * @throws IllegalArgumentException if the recurrence has fewer than one occurrence
     */
    public static List<TimeWindow> occurrencesOf(TimeWindow first, WeeklyRecurrence recurrence, ZoneId zone) {
        if (recurrence == null) {
            return List.of(first);
        }
        if (recurrence.occurrences() < 1) {
            throw new IllegalArgumentException("A recurrence has at least one occurrence.");
        }
        ZonedDateTime start = first.start().atZone(zone);
        ZonedDateTime end = first.end().atZone(zone);
        List<TimeWindow> occurrences = new ArrayList<>(recurrence.occurrences());
        for (int week = 0; week < recurrence.occurrences(); week++) {
            occurrences.add(new TimeWindow(
                    start.toLocalDateTime().plusWeeks(week).atZone(zone).toInstant(),
                    end.toLocalDateTime().plusWeeks(week).atZone(zone).toInstant()));
        }
        return List.copyOf(occurrences);
    }
}
