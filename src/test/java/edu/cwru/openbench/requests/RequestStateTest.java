package edu.cwru.openbench.requests;

import static edu.cwru.openbench.requests.RequestState.ALLOCATED;
import static edu.cwru.openbench.requests.RequestState.DRAFT;
import static edu.cwru.openbench.requests.RequestState.PENDING;
import static edu.cwru.openbench.requests.RequestState.REFUSED;
import static edu.cwru.openbench.requests.RequestState.SUBMITTED;
import static edu.cwru.openbench.requests.RequestState.WAITING;
import static edu.cwru.openbench.requests.RequestState.WITHDRAWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cwru.openbench.common.RuleViolation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lifecycle.States: every transition of design Table "Request transitions" passes, every other is refused. */
@DisplayName("Lifecycle.States: the Request state rules")
class RequestStateTest {

    /** The table's changes of state, from and to. */
    private static final Set<List<RequestState>> ALLOWED = Set.of(
            List.of(DRAFT, SUBMITTED),
            List.of(DRAFT, WITHDRAWN),
            List.of(SUBMITTED, ALLOCATED),
            List.of(SUBMITTED, PENDING),
            List.of(SUBMITTED, WAITING),
            List.of(PENDING, ALLOCATED),
            List.of(PENDING, WAITING),
            List.of(PENDING, REFUSED),
            List.of(PENDING, WITHDRAWN),
            List.of(WAITING, PENDING),
            List.of(WAITING, ALLOCATED),
            List.of(WAITING, DRAFT),
            List.of(WAITING, REFUSED),
            List.of(WAITING, WITHDRAWN),
            List.of(ALLOCATED, WAITING));

    @Test
    @DisplayName("every pair of states is allowed exactly when the table has the transition")
    void allowsExactlyTheTablesTransitions() {
        List<String> wrong = new ArrayList<>();
        for (RequestState from : RequestState.values()) {
            for (RequestState to : RequestState.values()) {
                if (from.canBecome(to) != ALLOWED.contains(List.of(from, to))) {
                    wrong.add(from + " -> " + to);
                }
            }
        }
        assertThat(wrong).as("pairs the rules get wrong").isEmpty();
    }

    @Test
    @DisplayName("Withdrawn and Refused are final; Allocated only goes back to Waiting through a Bump")
    void endsInTheFinalStates() {
        for (RequestState to : RequestState.values()) {
            assertThat(WITHDRAWN.canBecome(to)).isFalse();
            assertThat(REFUSED.canBecome(to)).isFalse();
            assertThat(ALLOCATED.canBecome(to)).isEqualTo(to == WAITING);
        }
    }

    @Test
    @DisplayName("a Request row moves only along an allowed transition and refuses any other with WRONG_STATE")
    void movesARowAlongTheRules() {
        RequestRow row = new RequestRow(1L, Instant.EPOCH, 1L);
        row.submit(Instant.EPOCH.plusSeconds(60));
        assertThat(row.state()).isEqualTo(SUBMITTED);
        assertThat(row.submittedAt()).isEqualTo(Instant.EPOCH.plusSeconds(60));

        row.moveTo(ALLOCATED);
        assertThat(row.state()).isEqualTo(ALLOCATED);

        assertThatThrownBy(() -> row.moveTo(WITHDRAWN))
                .isInstanceOf(RuleViolation.class)
                .extracting(e -> ((RuleViolation) e).code()).isEqualTo("WRONG_STATE");
        assertThatThrownBy(() -> row.submit(Instant.EPOCH))
                .isInstanceOf(RuleViolation.class)
                .extracting(e -> ((RuleViolation) e).code()).isEqualTo("WRONG_STATE");
        assertThat(row.state()).isEqualTo(ALLOCATED);
    }
}
