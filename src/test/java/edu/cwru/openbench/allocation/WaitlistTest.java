package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Exclusive.Waitlist.Join: the waitlist")
class WaitlistTest extends AllocationTest {

    private long rooms;
    private TimeWindow tuesday;
    private Instant now;

    @BeforeEach
    void createAFullRoom() {
        rooms = exclusiveType();
        room(rooms, "Room A");
        tuesday = window(7, 14, 16);
        now = clock.instant();
        commit(exclusiveLine(team, rooms, tuesday));
    }

    @Test
    @DisplayName("An unfit Required line joins at its Request's submission time and is first in line")
    void joinsAtTheSubmissionTime() {
        Instant submitted = now.minusSeconds(600);
        LineSnapshot line = exclusiveLine(team, rooms, window(7, 15, 17), 1, Optional.empty(), submitted);
        assertThat(evaluate(line).reason()).isEqualTo("NO_FREE_INSTANCE");

        join(line);

        assertThat(jdbc.queryForObject("SELECT joined_at FROM waitlist_entry WHERE line_id = ?", Timestamp.class,
                line.lineId().value()).toInstant()).isEqualTo(submitted);
        assertThat(position(line)).contains(1);
    }

    @Test
    @DisplayName("Positions follow submission time among overlapping windows, not the moment of joining")
    void ordersBySubmissionTime() {
        LineSnapshot later = waiting(tuesday, now.minusSeconds(60));
        LineSnapshot earlier = waiting(tuesday, now.minusSeconds(120));
        LineSnapshot elsewhen = waiting(window(8, 9, 10), now.minusSeconds(300));

        assertThat(position(earlier)).contains(1);
        assertThat(position(later)).contains(2);
        assertThat(position(elsewhen)).contains(1);
        assertThat(inTransaction(() -> waitlistable().waitingFor(new ResourceTypeId(rooms), tuesday)))
                .extracting(WaitingLine::lineId)
                .containsExactly(earlier.lineId(), later.lineId());
    }

    @Test
    @DisplayName("Bumped lines come first, then Priority lines, then by submission time")
    void bumpedThenPriorityThenSubmission() {
        LineSnapshot standard = waiting(tuesday, now.minusSeconds(900));
        LineSnapshot priority = waiting(withMarks(exclusiveLine(team, rooms, tuesday, 1, Optional.empty(),
                now.minusSeconds(60)), true, false));
        LineSnapshot bumped = waiting(withMarks(exclusiveLine(team, rooms, tuesday, 1, Optional.empty(),
                now.minusSeconds(30)), false, true));

        assertThat(position(bumped)).contains(1);
        assertThat(position(priority)).contains(2);
        assertThat(position(standard)).contains(3);
        assertThat(inTransaction(() -> waitlistable().waitingFor(new ResourceTypeId(rooms), tuesday)))
                .extracting(WaitingLine::lineId)
                .containsExactly(bumped.lineId(), priority.lineId(), standard.lineId());
    }

    @Test
    @DisplayName("Joining twice keeps one entry; leaving and cancel take the line off; Optional lines never join")
    void oneEntryPerLine() {
        LineSnapshot line = waiting(tuesday, now);
        join(line);
        assertThat(entries(line)).isEqualTo(1);

        inTransaction(() -> waitlistable().leave(line));
        assertThat(position(line)).isEmpty();

        join(line);
        cancel(line);
        assertThat(entries(line)).isZero();

        LineSnapshot optional = new LineSnapshot(line.lineId(), line.requestId(), line.teamId(), line.typeId(),
                line.allocationClass(), 1, null, line.window(), Optional.empty(), false, false, now, false);
        assertThatThrownBy(() -> join(optional)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Metered lines wait too")
    void meteredLinesWait() {
        assertThat(registry.capability(AllocationClass.METERED, Waitlistable.class)).isPresent();
        long gpus = meteredType();
        node(gpus, 2);
        LineSnapshot line = meteredLine(team, gpus, 4, tuesday);
        assertThat(evaluate(line).reason()).isEqualTo("POOL_FULL");

        inTransaction(() -> registry.capability(AllocationClass.METERED, Waitlistable.class).orElseThrow().join(line));

        assertThat(position(line)).contains(1);
    }

    private LineSnapshot waiting(TimeWindow window, Instant submittedAt) {
        return waiting(exclusiveLine(team, rooms, window, 1, Optional.empty(), submittedAt));
    }

    private LineSnapshot waiting(LineSnapshot line) {
        join(line);
        return line;
    }

    private static LineSnapshot withMarks(LineSnapshot line, boolean priority, boolean bumped) {
        return new LineSnapshot(line.lineId(), line.requestId(), line.teamId(), line.typeId(), line.allocationClass(),
                line.quantity(), line.units(), line.window(), line.namedResource(), line.required(), priority,
                line.submittedAt(), bumped);
    }

    private Waitlistable waitlistable() {
        return registry.capability(AllocationClass.EXCLUSIVE, Waitlistable.class).orElseThrow();
    }

    private void join(LineSnapshot line) {
        inTransaction(() -> registry.capability(line.allocationClass(), Waitlistable.class).orElseThrow().join(line));
    }

    private Optional<Integer> position(LineSnapshot line) {
        return inTransaction(() -> queries.waitlistPosition(line.lineId()));
    }

    private int entries(LineSnapshot line) {
        return jdbc.queryForObject("SELECT count(*) FROM waitlist_entry WHERE line_id = ?", Integer.class,
                line.lineId().value());
    }
}
