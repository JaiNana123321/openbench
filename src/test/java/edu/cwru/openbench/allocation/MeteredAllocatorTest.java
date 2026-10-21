package edu.cwru.openbench.allocation;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.RuleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Metered.Line.Form, Metered.Pool.Check: MeteredAllocator")
class MeteredAllocatorTest extends AllocationTest {

    private long gpus;
    private long otherTeam;

    @BeforeEach
    void createAPoolOfEight() {
        gpus = meteredType();
        node(gpus, 4);
        node(gpus, 4);
        otherTeam = testData().team(unique("Team"));
    }

    /**
     * The design's worked example through the real hourly SQL: A (another Team, 3 units, 10:00 to
     * 15:00) and B (the requesting Team, 4 units, 13:00 to 17:00) are allocated; the line asks for 2
     * units from 14:00 to 17:00.
     */
    @Nested
    @DisplayName("The worked example")
    class WorkedExample {

        private LineSnapshot line;

        @BeforeEach
        void allocateAAndB() {
            commit(meteredLine(otherTeam, gpus, 3, window(7, 10, 15)));
            commit(meteredLine(team, gpus, 4, window(7, 13, 17)));
            line = meteredLine(team, gpus, 2, window(7, 14, 17));
        }

        @Test
        @DisplayName("is POOL_FULL at 14:00, one unit short")
        void isPoolFullAtTwo() {
            Evaluation evaluation = evaluate(line);

            assertThat(evaluation.reason()).isEqualTo("POOL_FULL");
            assertThat(evaluation.message()).contains("pool of 8 units is 1 unit short at ").endsWith(", 14:00.");
        }

        @Test
        @DisplayName("reports the pool first when a Quota of 5 also fails")
        void reportsThePoolFirst() {
            testData().quota(team, gpus, 5);

            assertThat(evaluate(line).reason()).isEqualTo("POOL_FULL");
        }

        @Test
        @DisplayName("fails the Quota of 5 at 14:00 once the pool has room")
        void failsTheQuotaWhenThePoolHasRoom() {
            testData().quota(team, gpus, 5);
            node(gpus, 8);

            Evaluation evaluation = evaluate(line);

            assertThat(evaluation.reason()).isEqualTo("QUOTA_EXCEEDED");
            assertThat(evaluation.message()).contains("Quota of 5").contains("by 1 unit at ").endsWith(", 14:00.");
        }

        @Test
        @DisplayName("fits from 15:00, after A ends")
        void fitsAfterAEnds() {
            assertThat(evaluate(meteredLine(team, gpus, 4, window(7, 15, 17))).fits()).isTrue();
        }
    }

    @Test
    @DisplayName("A Hold blocks exactly like an allocation; cancel frees it")
    void aHoldBlocksLikeAnAllocation() {
        LineSnapshot held = meteredLine(otherTeam, gpus, 7, window(7, 9, 12));
        hold(held);
        assertThat(meteredStatesOf(held)).containsExactly("HELD");

        LineSnapshot line = meteredLine(team, gpus, 2, window(7, 11, 13));
        assertThat(evaluate(line).reason()).isEqualTo("POOL_FULL");

        cancel(held);
        cancel(held);
        assertThat(meteredStatesOf(held)).containsExactly("EXPIRED");
        assertThat(evaluate(line).fits()).isTrue();
    }

    @Test
    @DisplayName("Only the requesting Team's own units count toward its Quota")
    void theQuotaCountsOnlyTheTeam() {
        testData().quota(team, gpus, 3);
        commit(meteredLine(otherTeam, gpus, 5, window(7, 9, 12)));

        assertThat(evaluate(meteredLine(team, gpus, 3, window(7, 9, 12))).fits()).isTrue();
        assertThat(evaluate(meteredLine(team, gpus, 4, window(7, 9, 12))).reason()).isEqualTo("POOL_FULL");

        node(gpus, 8);
        assertThat(evaluate(meteredLine(team, gpus, 4, window(7, 9, 12))).reason()).isEqualTo("QUOTA_EXCEEDED");
    }

    @Test
    @DisplayName("Hold then commit turns the one row ALLOCATED with the line's units and window")
    void holdsAndCommits() {
        LineSnapshot line = meteredLine(team, gpus, 8, window(7, 9, 17));
        hold(line);
        commit(line);

        assertThat(meteredStatesOf(line)).containsExactly("ALLOCATED");
        assertThat(jdbc.queryForMap("SELECT units, type_id, team_id FROM metered_allocation WHERE line_id = ?",
                line.lineId().value()))
                .containsEntry("units", 8).containsEntry("type_id", gpus).containsEntry("team_id", team);
    }

    @Test
    @DisplayName("Commit with no Hold first checks the fit again and refuses with the earliest hour")
    void commitsWithoutAHold() {
        commit(meteredLine(otherTeam, gpus, 6, window(7, 12, 14)));
        LineSnapshot line = meteredLine(team, gpus, 3, window(7, 10, 14));

        assertThatThrownBy(() -> commit(line)).isInstanceOfSatisfying(RuleViolation.class, e -> {
            assertThat(e.code()).isEqualTo("POOL_FULL");
            assertThat(e.properties()).containsEntry("earliestHour", at(7, 12));
        });
        assertThat(meteredStatesOf(line)).isEmpty();
    }

    @Test
    @DisplayName("Release marks the row RELEASED and frees the units; back-to-back windows share the pool")
    void releaseFreesAndBackToBackFits() {
        LineSnapshot morning = meteredLine(team, gpus, 8, window(7, 9, 12));
        commit(morning);
        LineSnapshot afternoon = meteredLine(otherTeam, gpus, 8, window(7, 12, 15));
        commit(afternoon);
        assertThat(meteredStatesOf(afternoon)).containsExactly("ALLOCATED");

        LineSnapshot again = meteredLine(otherTeam, gpus, 1, window(7, 11, 12));
        assertThat(evaluate(again).reason()).isEqualTo("POOL_FULL");
        release(morning);
        assertThat(meteredStatesOf(morning)).containsExactly("RELEASED");
        assertThat(evaluate(again).fits()).isTrue();
    }
}
