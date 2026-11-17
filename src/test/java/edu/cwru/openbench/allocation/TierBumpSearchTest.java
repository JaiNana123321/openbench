package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.allocation.HourlyLoad.Hour;
import edu.cwru.openbench.allocation.TierBumpSearch.Candidate;
import edu.cwru.openbench.allocation.TierBumpSearch.Span;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tier Bump plan's choice, without Spring (issue #23): only strictly lower tiers of other
 * Requests outside the cutoff, lowest tier first, then the latest-submitted, only as many as every
 * hour needs, and none when the line would still exceed its Team's Quota.
 */
@DisplayName("Metered.Tier.Bump: TierBumpSearch")
class TierBumpSearchTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant CUTOFF = NOW.plus(Duration.ofHours(24));
    private static final Instant TEN = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant ELEVEN = TEN.plus(Duration.ofHours(1));
    private static final Instant NOON = TEN.plus(Duration.ofHours(2));
    private static final BigDecimal EXPRESS = new BigDecimal("1.5");
    private static final BigDecimal STANDARD = BigDecimal.ONE;
    private static final long OWN_REQUEST = 100;
    private static final long OWN_TEAM = 1;
    private static final long OTHER_TEAM = 2;

    @Test
    @DisplayName("met-tier-bump: Express 4 units Bump the Standard line holding the whole pool of 16")
    void bumpsTheStandardLine() {
        Candidate o1 = line(11, OTHER_TEAM, STANDARD, "2026-10-01T09:00:00Z", 16, TEN, NOON);
        assertThat(choose(4, hours(16, 16), Optional.empty(), o1)).hasValue(List.of(o1));
    }

    @Test
    @DisplayName("no tier first, then the lower tier; among equals the latest-submitted; only as many as needed")
    void bumpsTheLowestRankLatestSubmittedFirst() {
        Candidate standard = line(11, OTHER_TEAM, STANDARD, "2026-10-03T09:00:00Z", 6, TEN, NOON);
        Candidate untieredEarly = line(12, OTHER_TEAM, null, "2026-10-01T09:00:00Z", 6, TEN, NOON);
        Candidate untieredLate = line(13, OTHER_TEAM, null, "2026-10-02T09:00:00Z", 4, TEN, NOON);
        assertThat(choose(4, hours(16, 16), Optional.empty(), standard, untieredEarly, untieredLate))
                .hasValue(List.of(untieredLate));
        assertThat(choose(8, hours(16, 16), Optional.empty(), standard, untieredEarly, untieredLate))
                .hasValue(List.of(untieredEarly, untieredLate));
    }

    @Test
    @DisplayName("a Bump the later picks make unnecessary is given back")
    void givesBackUnneededBumps() {
        // The untiered 2 units only cover 10:00; the Standard 8 units cover both hours and alone suffice.
        Candidate untieredShort = line(12, OTHER_TEAM, null, "2026-10-02T09:00:00Z", 2, TEN, ELEVEN);
        Candidate standard = line(11, OTHER_TEAM, STANDARD, "2026-10-01T09:00:00Z", 8, TEN, NOON);
        assertThat(choose(4, hours(16, 14), Optional.empty(), untieredShort, standard)).hasValue(List.of(standard));
    }

    @Test
    @DisplayName("never an equal or higher tier, the line's own Request, or a line starting inside the cutoff")
    void leavesProtectedLinesAlone() {
        Candidate express = line(11, OTHER_TEAM, EXPRESS, "2026-10-02T09:00:00Z", 16, TEN, NOON);
        assertThat(choose(4, hours(16, 16), Optional.empty(), express)).isEmpty();
        Candidate own = new Candidate(12, OWN_REQUEST, OWN_TEAM, null, null, TEN, false, List.of(new Span(16, TEN, NOON)));
        assertThat(choose(4, hours(16, 16), Optional.empty(), own)).isEmpty();
        Instant soon = NOW.plus(Duration.ofHours(3));
        Candidate inside = line(13, OTHER_TEAM, null, "2026-10-02T09:00:00Z", 16, soon, soon.plus(Duration.ofHours(2)));
        assertThat(TierBumpSearch.bumpable(inside, EXPRESS, OWN_REQUEST, CUTOFF)).isFalse();
    }

    @Test
    @DisplayName("no plan when too little can be Bumped, when the pool is not full, or past the Team's Quota")
    void noPlan() {
        Candidate small = line(11, OTHER_TEAM, null, "2026-10-02T09:00:00Z", 2, TEN, NOON);
        assertThat(choose(4, hours(16, 16), Optional.empty(), small)).isEmpty();
        assertThat(choose(4, hours(10, 10), Optional.empty(), small)).isEmpty();
        Candidate other = line(12, OTHER_TEAM, null, "2026-10-02T09:00:00Z", 10, TEN, NOON);
        // The own Team holds 6 of a Quota of 8 in both hours.
        List<Hour> withTeam = List.of(new Hour(TEN, 16, 6), new Hour(ELEVEN, 16, 6));
        assertThat(choose(4, withTeam, Optional.of(8), other)).isEmpty();
        assertThat(choose(2, withTeam, Optional.of(8), other)).hasValue(List.of(other));
    }

    private static Optional<List<Candidate>> choose(int units, List<Hour> hours, Optional<Integer> quota,
            Candidate... candidates) {
        return TierBumpSearch.choose(units, EXPRESS, OWN_REQUEST, OWN_TEAM, hours, 16, quota, List.of(candidates),
                CUTOFF);
    }

    private static List<Hour> hours(int usedAtTen, int usedAtEleven) {
        return List.of(new Hour(TEN, usedAtTen, 0), new Hour(ELEVEN, usedAtEleven, 0));
    }

    private static Candidate line(long id, long team, BigDecimal premium, String submittedAt, int units, Instant start,
            Instant end) {
        return new Candidate(id, id + 1000, team, premium, Instant.parse(submittedAt), start, false,
                List.of(new Span(units, start, end)));
    }
}
