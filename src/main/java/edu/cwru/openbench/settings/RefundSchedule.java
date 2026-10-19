package edu.cwru.openbench.settings;

import java.util.Comparator;
import java.util.List;

/**
 * The Refund Schedule of a Resource Type: a Release at least a step's minimum lead time before a
 * part starts returns that step's fraction of the part's Charge. Steps are kept by ascending lead
 * time. Settings refuses a fraction of 1 or more; {@code Pricing.refund} asserts it.
 */
public record RefundSchedule(List<RefundStep> steps) {

    public static final RefundSchedule NONE = new RefundSchedule(List.of());

    public RefundSchedule {
        steps = steps.stream().sorted(Comparator.comparing(RefundStep::minLead)).toList();
    }
}
