package edu.cwru.openbench.members;

import edu.cwru.openbench.common.DateRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** How a Member's availability ranges are kept: merged, so that one range contains any window they cover. */
final class Availability {

    private Availability() {}

    /**
     * The ranges sorted by first day, with overlapping ranges and ranges that touch (one ends the day
     * before the next starts) joined into one.
     */
    static List<DateRange> merge(List<DateRange> ranges) {
        List<DateRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(DateRange::from));
        List<DateRange> merged = new ArrayList<>();
        for (DateRange range : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && !range.from().isAfter(merged.get(last).to().plusDays(1))) {
                DateRange previous = merged.get(last);
                merged.set(last, new DateRange(previous.from(),
                        range.to().isAfter(previous.to()) ? range.to() : previous.to()));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }
}
