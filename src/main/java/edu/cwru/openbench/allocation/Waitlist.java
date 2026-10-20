package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The waitlist rules both waitlistable allocators share (design Section 4.5, "Waitlist"): one entry
 * per waiting Required line, joined at its Request's submission time. Runs in the caller's
 * transaction.
 */
@Component
class Waitlist {

    private final WaitlistEntryRepository entries;

    Waitlist(WaitlistEntryRepository entries) {
        this.entries = entries;
    }

    void join(LineSnapshot line) {
        if (!line.required()) {
            throw new IllegalArgumentException("An Optional line never joins the waitlist.");
        }
        if (entries.existsByLineId(line.lineId().value())) {
            return;
        }
        entries.saveAndFlush(new WaitlistEntryRow(line.lineId().value(), line.typeId().value(),
                line.window().start(), line.window().end(), line.priority(), line.bumped(), line.submittedAt()));
    }

    void leave(LineSnapshot line) {
        entries.deleteByLine(line.lineId().value());
    }

    List<WaitingLine> waitingFor(ResourceTypeId type, TimeWindow freed) {
        return entries.overlapping(type.value(), freed.start(), freed.end()).stream()
                .map(WaitlistEntryRow::toWaitingLine)
                .toList();
    }
}
