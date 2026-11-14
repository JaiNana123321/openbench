package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Bump plan's choice, without Spring (design Section 4.5, "Bump plan" and its worked example):
 * fewest Bumps, ties toward the latest-submitted, never a Priority line, never inside the cutoff.
 */
@DisplayName("Exclusive.Priority.Bump: BumpSearch")
class BumpSearchTest {

    /** Monday, October 5, 2026, 10:00, when the Priority line is submitted. */
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    /** A 24-hour bump cutoff. */
    private static final Instant CUTOFF = NOW.plus(Duration.ofHours(24));

    private static final long OWN_REQUEST = 100;

    private static final long ROOM_1 = 1;
    private static final long ROOM_2 = 2;
    private static final long ROOM_3 = 3;

    @Test
    @DisplayName("the worked example: Room 2 needs one Bump (L3), Room 1 two (L1 and L2), so L3 is Bumped")
    void bumpsTheInstanceNeedingFewestBumps() {
        BumpSearch.Blocker l1 = blocker(11, false, "2026-09-28T09:00:00Z", "2026-10-08T13:00:00Z");
        BumpSearch.Blocker l2 = blocker(12, false, "2026-10-01T09:00:00Z", "2026-10-08T15:00:00Z");
        BumpSearch.Blocker l3 = blocker(13, false, "2026-09-30T09:00:00Z", "2026-10-08T09:00:00Z");

        Optional<BumpSearch.Choice> choice = BumpSearch.choose(1,
                List.of(instance(ROOM_1, l1, l2), instance(ROOM_2, l3)), OWN_REQUEST, CUTOFF);

        assertThat(choice).isPresent();
        assertThat(choice.get().instances()).containsExactly(ROOM_2);
        assertThat(choice.get().bumped()).containsExactly(l3);
    }

    @Test
    @DisplayName("the worked example: had L3 an occurrence on Tuesday at 09:00, inside the cutoff, L1 and L2 are Bumped on Room 1")
    void skipsAnInstanceWhoseLineHasAnOccurrenceInsideTheCutoff() {
        BumpSearch.Blocker l1 = blocker(11, false, "2026-09-28T09:00:00Z", "2026-10-08T13:00:00Z");
        BumpSearch.Blocker l2 = blocker(12, false, "2026-10-01T09:00:00Z", "2026-10-08T15:00:00Z");
        // L3's earliest live occurrence is Tuesday 09:00, before Monday 10:00 plus 24 hours.
        BumpSearch.Blocker l3 = blocker(13, false, "2026-09-30T09:00:00Z", "2026-10-06T09:00:00Z");

        Optional<BumpSearch.Choice> choice = BumpSearch.choose(1,
                List.of(instance(ROOM_1, l1, l2), instance(ROOM_2, l3)), OWN_REQUEST, CUTOFF);

        assertThat(choice).isPresent();
        assertThat(choice.get().instances()).containsExactly(ROOM_1);
        assertThat(choice.get().bumped()).containsExactly(l1, l2);
    }

    @Test
    @DisplayName("the worked example: had L1 also been a Priority line, there is no plan and the line waits")
    void neverBumpsAPriorityLine() {
        BumpSearch.Blocker l1 = blocker(11, true, "2026-09-28T09:00:00Z", "2026-10-08T13:00:00Z");
        BumpSearch.Blocker l2 = blocker(12, false, "2026-10-01T09:00:00Z", "2026-10-08T15:00:00Z");
        BumpSearch.Blocker l3 = blocker(13, false, "2026-09-30T09:00:00Z", "2026-10-06T09:00:00Z");

        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, l1, l2), instance(ROOM_2, l3)), OWN_REQUEST, CUTOFF))
                .isEmpty();
    }

    @Test
    @DisplayName("one Bump on each instance: the latest-submitted line is Bumped (exc-bump)")
    void breaksTiesTowardTheLatestSubmitted() {
        BumpSearch.Blocker first = blocker(21, false, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");
        BumpSearch.Blocker second = blocker(22, false, "2026-09-29T09:00:00Z", "2026-10-21T10:00:00Z");

        Optional<BumpSearch.Choice> choice = BumpSearch.choose(1,
                List.of(instance(ROOM_1, first), instance(ROOM_2, second)), OWN_REQUEST, CUTOFF);

        assertThat(choice.orElseThrow().instances()).containsExactly(ROOM_2);
        assertThat(choice.get().bumped()).containsExactly(second);
    }

    @Test
    @DisplayName("an occurrence starting exactly at the cutoff is outside it; one an hour earlier is inside")
    void treatsTheCutoffAsHalfOpen() {
        BumpSearch.Blocker atCutoff = blocker(31, false, "2026-09-28T09:00:00Z", CUTOFF.toString());
        BumpSearch.Blocker before = blocker(32, false, "2026-09-28T09:00:00Z", CUTOFF.minusSeconds(3600).toString());

        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, atCutoff)), OWN_REQUEST, CUTOFF)).isPresent();
        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, before)), OWN_REQUEST, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("a line of the Priority line's own Request is never Bumped")
    void neverBumpsItsOwnRequest() {
        BumpSearch.Blocker own = new BumpSearch.Blocker(41, OWN_REQUEST, 7, false,
                Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-21T10:00:00Z"), true);

        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, own)), OWN_REQUEST, CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("no plan when enough instances are free: the line fits without a Bump")
    void plansNothingWhenFreeInstancesSuffice() {
        BumpSearch.Blocker standard = blocker(51, false, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");

        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, standard), instance(ROOM_2)), OWN_REQUEST, CUTOFF))
                .isEmpty();
    }

    @Test
    @DisplayName("quantity 2: the free instance and the cheapest clearable one; a line blocking two instances counts once")
    void takesFreeInstancesThenClearsTheRest() {
        BumpSearch.Blocker wide = blocker(61, false, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");
        BumpSearch.Blocker narrow = blocker(62, false, "2026-09-30T09:00:00Z", "2026-10-21T10:00:00Z");
        BumpSearch.Blocker other = blocker(63, false, "2026-10-01T09:00:00Z", "2026-10-21T10:00:00Z");

        Optional<BumpSearch.Choice> withFree = BumpSearch.choose(2,
                List.of(instance(ROOM_1, wide, narrow), instance(ROOM_2), instance(ROOM_3, other)), OWN_REQUEST, CUTOFF);
        assertThat(withFree.orElseThrow().instances()).containsExactly(ROOM_2, ROOM_3);
        assertThat(withFree.get().bumped()).containsExactly(other);

        // Rooms 1 and 2 are both blocked by the same wide line: clearing both Bumps it once.
        Optional<BumpSearch.Choice> shared = BumpSearch.choose(2,
                List.of(instance(ROOM_1, wide), instance(ROOM_2, wide), instance(ROOM_3, narrow, other)), OWN_REQUEST,
                CUTOFF);
        assertThat(shared.orElseThrow().instances()).containsExactly(ROOM_1, ROOM_2);
        assertThat(shared.get().bumped()).containsExactly(wide);
    }

    @Test
    @DisplayName("too few instances can be cleared: no plan")
    void plansNothingWhenTooFewCanBeCleared() {
        BumpSearch.Blocker standard = blocker(71, false, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");
        BumpSearch.Blocker priority = blocker(72, true, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");

        assertThat(BumpSearch.choose(2, List.of(instance(ROOM_1, standard), instance(ROOM_2, priority)), OWN_REQUEST,
                CUTOFF)).isEmpty();
    }

    @Test
    @DisplayName("a line written without a submission time counts as submitted first, so it is Bumped last")
    void treatsAMissingSubmissionTimeAsEarliest() {
        BumpSearch.Blocker unknown = new BumpSearch.Blocker(81, 81, 7, false, null,
                Instant.parse("2026-10-21T10:00:00Z"), false);
        BumpSearch.Blocker known = blocker(82, false, "2026-09-28T09:00:00Z", "2026-10-21T10:00:00Z");

        assertThat(BumpSearch.choose(1, List.of(instance(ROOM_1, unknown), instance(ROOM_2, known)), OWN_REQUEST,
                CUTOFF).orElseThrow().bumped()).containsExactly(known);
    }

    /** An Allocated line of its own Request and Team, both numbered like the line. */
    private static BumpSearch.Blocker blocker(long line, boolean priority, String submittedAt, String firstStart) {
        return new BumpSearch.Blocker(line, line, line, priority, Instant.parse(submittedAt), Instant.parse(firstStart),
                false);
    }

    private static BumpSearch.Instance instance(long resource, BumpSearch.Blocker... blockers) {
        return new BumpSearch.Instance(resource, List.of(blockers));
    }
}
