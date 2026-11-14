package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.ledger.LedgerService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A Bump is part of its submission (design scenario S5): if the Priority line's own Charge fails,
 * the whole submission rolls back, so nothing is Bumped, refunded, waitlisted, or told. The Ledger
 * here is the real one, made to refuse the Priority Request's Charges with
 * {@code INSUFFICIENT_BALANCE}, as a Balance spent between the early check and the account lock
 * would.
 */
class BumpRollbackTest extends BumpWorldTest {

    @MockitoSpyBean
    private LedgerService spiedLedger;

    @Test
    @DisplayName("The Priority line's Charge fails with INSUFFICIENT_BALANCE: nothing is Bumped, refunded, or waitlisted")
    void rollsBackTheBumpWithTheSubmission() throws Exception {
        LocalDate d = dayD();
        long[] blocking = submitted(oscar, room, at(d, 10), at(d, 12), roomB, false);
        long draft = createDraft(as(alice));
        long line = line(as(alice), draft, room, 1, at(d, 10), at(d, 12), roomB);
        setPriority(as(alice), draft, line, true).andExpect(status().isOk());
        doThrow(new RuleViolation("INSUFFICIENT_BALANCE", "The Available Balance no longer covers the Charges."))
                .when(spiedLedger).commitCharges(eq(new RequestId(draft)), anyList());

        nextSecond();
        submit(as(alice), draft)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        detail(as(alice), draft).andExpect(jsonPath("$.request.state").value("DRAFT"));
        assertThat(rowStates(line)).isEmpty();
        assertThat(rowStates(blocking[1])).containsExactly("ALLOCATED");
        assertThat(refundsOf(blocking[1])).isEmpty();
        assertThat(waitlistEntryOf(blocking[1])).isEmpty();
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).isEmpty();
        detail(as(oscar), blocking[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"));
        assertThat(available(operations)).isEqualByComparingTo("970.00");
        assertThat(available(platform)).isEqualByComparingTo("5000.00");
    }
}
