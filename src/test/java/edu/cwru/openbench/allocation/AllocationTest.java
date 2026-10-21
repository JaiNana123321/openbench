package edu.cwru.openbench.allocation;

import static edu.cwru.openbench.TestData.unique;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the Allocation integration tests. The allocators run only inside a transaction
 * ({@code Propagation.MANDATORY}), so each step opens its own, as a Requests transition would: lock
 * the line's type, then call the allocator. Rows for the Request and line foreign keys are written
 * with plain SQL; windows lie a week or more ahead of the real clock, in company time.
 */
abstract class AllocationTest extends PostgresTest {

    @Autowired
    protected Clock clock;

    @Autowired
    protected AllocationLock lock;

    @Autowired
    protected AllocatorRegistry registry;

    @Autowired
    protected AllocationQueries queries;

    @Autowired
    private PlatformTransactionManager transactionManager;

    protected TransactionTemplate tx;

    protected long team;

    protected long owner;

    /** The username of {@link #owner}, a Resource Owner who owns every Resource these tests add. */
    protected String ownerUsername;

    @BeforeEach
    void createATeamAndAnOwner() {
        tx = new TransactionTemplate(transactionManager);
        testData().periodContaining(LocalDate.now(clock));
        team = testData().team(unique("Team"));
        ownerUsername = unique("owner");
        owner = testData().member(team, ownerUsername, Role.RESOURCE_OWNER);
    }

    // ---------------------------------------------------------------- time

    /** The hour on the day {@code daysAhead} days from today, company time. */
    protected Instant at(int daysAhead, int hour) {
        return LocalDate.now(clock).plusDays(daysAhead).atTime(hour, 0).atZone(clock.getZone()).toInstant();
    }

    /** From {@code fromHour} to {@code toHour} on the day {@code daysAhead} days from today. */
    protected TimeWindow window(int daysAhead, int fromHour, int toHour) {
        return new TimeWindow(at(daysAhead, fromHour), at(daysAhead, toHour));
    }

    // ---------------------------------------------------------------- inventory

    protected long exclusiveType() {
        return testData().resourceType(unique("Room"), "EXCLUSIVE");
    }

    protected long meteredType() {
        return testData().resourceType(unique("GPU"), "METERED");
    }

    protected long room(long type, String name) {
        return testData().resource(type, name, owner, 1);
    }

    protected long node(long type, int units) {
        return testData().resource(type, unique("node"), owner, units);
    }

    // ---------------------------------------------------------------- lines

    /** A Required Exclusive line of a new Request of the Team, for one instance of the type. */
    protected LineSnapshot exclusiveLine(long teamId, long type, TimeWindow window) {
        return exclusiveLine(teamId, type, window, 1, Optional.empty(), clock.instant());
    }

    /** A Required Exclusive line naming one Resource. */
    protected LineSnapshot namedLine(long teamId, long type, TimeWindow window, long resource) {
        return exclusiveLine(teamId, type, window, 1, Optional.of(new ResourceId(resource)), clock.instant());
    }

    protected LineSnapshot exclusiveLine(long teamId, long type, TimeWindow window, int quantity,
            Optional<ResourceId> named, Instant submittedAt) {
        long request = testData().submittedRequest(teamId, submittedAt);
        long line = testData().exclusiveLine(request, type, quantity, window.start(), window.end());
        return new LineSnapshot(new RequestLineId(line), new RequestId(request), new TeamId(teamId),
                new ResourceTypeId(type), AllocationClass.EXCLUSIVE, quantity, null, window, named, true, false,
                submittedAt, false);
    }

    /** A Required Metered line of a new Request of the Team. */
    protected LineSnapshot meteredLine(long teamId, long type, int units, TimeWindow window) {
        Instant submittedAt = clock.instant();
        long request = testData().submittedRequest(teamId, submittedAt);
        long line = testData().meteredLine(request, type, units, window.start(), window.end());
        return new LineSnapshot(new RequestLineId(line), new RequestId(request), new TeamId(teamId),
                new ResourceTypeId(type), AllocationClass.METERED, 1, units, window, Optional.empty(), true, false,
                submittedAt, false);
    }

    // ---------------------------------------------------------------- one transition per call

    protected <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    protected void inTransaction(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    protected Allocator allocatorOf(LineSnapshot line) {
        return registry.forClass(line.allocationClass());
    }

    protected void lockTypeOf(LineSnapshot line) {
        lock.lockTypes(new TreeSet<>(List.of(line.typeId())));
    }

    protected Evaluation evaluate(LineSnapshot line) {
        return inTransaction(() -> {
            lockTypeOf(line);
            return allocatorOf(line).evaluate(line);
        });
    }

    protected void hold(LineSnapshot line) {
        inTransaction(() -> {
            lockTypeOf(line);
            allocatorOf(line).hold(line);
        });
    }

    protected void commit(LineSnapshot line) {
        inTransaction(() -> {
            lockTypeOf(line);
            allocatorOf(line).commit(line);
        });
    }

    protected void cancel(LineSnapshot line) {
        inTransaction(() -> {
            lockTypeOf(line);
            allocatorOf(line).cancel(line);
        });
    }

    protected void release(LineSnapshot line) {
        inTransaction(() -> {
            lockTypeOf(line);
            allocatorOf(line).release(line, ReleasePart.wholeLine());
        });
    }

    // ---------------------------------------------------------------- rows

    /** The line's Exclusive occurrences as "resource:state", by id. */
    protected List<String> occurrencesOf(LineSnapshot line) {
        return jdbc.queryForList("SELECT resource_id || ':' || state FROM exclusive_occurrence WHERE line_id = ? ORDER BY id",
                String.class, line.lineId().value());
    }

    /** The states of the line's Metered rows, by id. */
    protected List<String> meteredStatesOf(LineSnapshot line) {
        return jdbc.queryForList("SELECT state FROM metered_allocation WHERE line_id = ? ORDER BY id",
                String.class, line.lineId().value());
    }
}
