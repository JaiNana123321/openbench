package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which allocated rows a {@link ReleasePart} gives back, and how (design Section 4.5, "release marks
 * given-back occurrences (or a shortened tail) RELEASED"). Pure: the allocators read the line's
 * ALLOCATED rows, ask for a plan, and apply its steps; a preview asks for the same plan and writes
 * nothing.
 *
 * <p>An Exclusive line has one row per occurrence and instance, a Metered line one row for its
 * window. A row's start is its occurrence's start, also after an earlier end has shortened it.
 *
 * <ul>
 * <li>{@link ReleasePart.WholeLine}: every row that has not started.
 * <li>{@link ReleasePart.Occurrences}: the rows that start at the named instants; none may have started.
 * <li>{@link ReleasePart.EarlierEnd}: rows that start at or after the new end whole, and the row
 *     that runs across it shortened to it; the new end lies after the first row's start, on a whole
 *     hour, and not in the past. The hours given back may belong to an occurrence already running.
 * <li>{@link ReleasePart.FewerUnits}: the Metered row keeps fewer units over its window; it must not
 *     have started.
 * </ul>
 */
final class ReleasePlan {

    static final String NOT_ALLOCATED = "NOT_ALLOCATED";

    /** An ALLOCATED row: its id, its window, and its instances (1) or units. */
    record Row(Long id, TimeWindow window, int amount) {}

    /** What to do with one row. */
    sealed interface Step {

        Row row();

        /** The whole row becomes RELEASED. */
        record ReleaseRow(Row row) implements Step {}

        /** The row ends at {@code newEnd}; a RELEASED row records the tail. */
        record Shorten(Row row, Instant newEnd) implements Step {}

        /** The row keeps {@code newUnits}; a RELEASED row records the units given back. */
        record Reduce(Row row, int newUnits) implements Step {}
    }

    private final List<Step> steps;
    private final boolean nothingLeft;

    private ReleasePlan(List<Step> steps, boolean nothingLeft) {
        this.steps = List.copyOf(steps);
        this.nothingLeft = nothingLeft;
    }

    List<Step> steps() {
        return steps;
    }

    /**
     * What is given back, one piece per occurrence and released window, with the instances or units
     * summed over the occurrence's rows, by start.
     */
    Released released() {
        Map<List<Instant>, ReleasedPiece> pieces = new LinkedHashMap<>();
        for (Step step : steps) {
            TimeWindow occurrence = step.row().window();
            ReleasedPiece piece = switch (step) {
                case Step.ReleaseRow s -> new ReleasedPiece(occurrence, occurrence, s.row().amount());
                case Step.Shorten s -> new ReleasedPiece(occurrence, new TimeWindow(s.newEnd(), occurrence.end()),
                        s.row().amount());
                case Step.Reduce s -> new ReleasedPiece(occurrence, occurrence, s.row().amount() - s.newUnits());
            };
            pieces.merge(List.of(occurrence.start(), piece.released().start(), piece.released().end()), piece,
                    (a, b) -> new ReleasedPiece(a.occurrence(), a.released(), a.amount() + b.amount()));
        }
        List<ReleasedPiece> ordered = pieces.values().stream()
                .sorted(Comparator.comparing((ReleasedPiece p) -> p.occurrence().start()))
                .toList();
        return new Released(ordered, nothingLeft);
    }

    /**
     * The plan for the part.
     *
     * @param rows the line's ALLOCATED rows
     * @param metered whether the line is Metered, the only class that can keep fewer units
     * @param now the current instant: what has started can no longer be given back
     * @param zone the company zone, in which a new end lies on a whole hour
     * @param what the line's type, for messages
     * @throws RuleViolation {@code NOT_ALLOCATED} when the part does not lie inside the live allocation
     *     or has already started; {@code WINDOW_INVALID} for a new end off the hour;
     *     {@code QUANTITY_INVALID} unless the new units are at least 1 and fewer than now;
     *     {@code VALIDATION_FAILED} for fewer units of an Exclusive line
     */
    static ReleasePlan of(List<Row> rows, ReleasePart part, boolean metered, Instant now, ZoneId zone, String what) {
        if (rows.isEmpty()) {
            throw new RuleViolation(NOT_ALLOCATED, "This line holds no allocated " + what + ".");
        }
        List<Row> sorted = rows.stream()
                .sorted(Comparator.comparing((Row row) -> row.window().start()).thenComparing(Row::id))
                .toList();
        List<Step> steps = switch (part) {
            case ReleasePart.WholeLine whole -> wholeLine(sorted, now);
            case ReleasePart.Occurrences occurrences -> occurrences(sorted, occurrences, now, zone);
            case ReleasePart.EarlierEnd earlier -> earlierEnd(sorted, earlier.newEnd(), now, zone);
            case ReleasePart.FewerUnits fewer -> fewerUnits(sorted, fewer.newUnits(), metered, now);
        };
        Set<Long> released = steps.stream()
                .filter(step -> step instanceof Step.ReleaseRow)
                .map(step -> step.row().id())
                .collect(Collectors.toSet());
        return new ReleasePlan(steps, released.size() == rows.size());
    }

    private static List<Step> wholeLine(List<Row> rows, Instant now) {
        List<Step> steps = rows.stream()
                .filter(row -> !started(row, now))
                .<Step>map(Step.ReleaseRow::new)
                .toList();
        if (steps.isEmpty()) {
            throw new RuleViolation(NOT_ALLOCATED, "Every allocated occurrence of this line has started; "
                    + "give back the hours after an earlier end instead.");
        }
        return steps;
    }

    private static List<Step> occurrences(List<Row> rows, ReleasePart.Occurrences part, Instant now, ZoneId zone) {
        List<Step> steps = new ArrayList<>();
        for (Instant start : part.starts()) {
            List<Row> matching = rows.stream().filter(row -> row.window().start().equals(start)).toList();
            if (matching.isEmpty()) {
                throw new RuleViolation(NOT_ALLOCATED, "No allocated occurrence of this line starts at "
                        + Windows.describe(start, zone) + ".");
            }
            if (!start.isAfter(now)) {
                throw new RuleViolation(NOT_ALLOCATED, "The occurrence of " + Windows.describe(start, zone)
                        + " has started; it can no longer be given back.");
            }
            matching.forEach(row -> steps.add(new Step.ReleaseRow(row)));
        }
        return steps;
    }

    private static List<Step> earlierEnd(List<Row> rows, Instant newEnd, Instant now, ZoneId zone) {
        if (newEnd.atZone(zone).getMinute() != 0 || newEnd.getEpochSecond() % 60 != 0 || newEnd.getNano() != 0) {
            throw new RuleViolation("WINDOW_INVALID", "The new end must be on a whole hour.");
        }
        if (newEnd.isBefore(now)) {
            throw new RuleViolation(NOT_ALLOCATED, "The hours before now are consumed; the new end cannot be "
                    + Windows.describe(newEnd, zone) + ".");
        }
        Instant firstStart = rows.getFirst().window().start();
        Instant lastEnd = rows.stream().map(row -> row.window().end()).max(Comparator.naturalOrder()).orElseThrow();
        if (!newEnd.isAfter(firstStart) || !newEnd.isBefore(lastEnd)) {
            throw new RuleViolation(NOT_ALLOCATED, "The new end must lie inside the allocation, after "
                    + Windows.describe(firstStart, zone) + " and before " + Windows.describe(lastEnd, zone) + ".");
        }
        List<Step> steps = new ArrayList<>();
        for (Row row : rows) {
            if (!row.window().start().isBefore(newEnd)) {
                steps.add(new Step.ReleaseRow(row));
            } else if (row.window().end().isAfter(newEnd)) {
                steps.add(new Step.Shorten(row, newEnd));
            }
        }
        return steps;
    }

    private static List<Step> fewerUnits(List<Row> rows, int newUnits, boolean metered, Instant now) {
        if (!metered) {
            throw new RuleViolation("VALIDATION_FAILED",
                    "Only a Metered line keeps fewer units; give back occurrences or an earlier end instead.");
        }
        Row row = rows.getFirst();
        if (newUnits < 1 || newUnits >= row.amount()) {
            throw new RuleViolation("QUANTITY_INVALID", "The line holds " + row.amount()
                    + " units; it can keep from 1 to " + (row.amount() - 1) + " of them.");
        }
        if (started(row, now)) {
            throw new RuleViolation(NOT_ALLOCATED,
                    "The window has started; give back the hours after an earlier end instead.");
        }
        return List.of(new Step.Reduce(row, newUnits));
    }

    private static boolean started(Row row, Instant now) {
        return !row.window().start().isAfter(now);
    }
}
