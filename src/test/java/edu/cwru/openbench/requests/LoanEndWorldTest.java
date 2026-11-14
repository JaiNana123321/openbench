package edu.cwru.openbench.requests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base of the early-Loan-end tests (issue #8): the Matched test world, a Loan of Dave Kim to Research
 * formed through the API as in mat-loan-form, and the calls and rows those tests read.
 */
abstract class LoanEndWorldTest extends MatchedWorldTest {

    /** A Request of Bob's, Allocated, with one Person line on which Dave Kim is lent to Research for the days. */
    protected record Formed(long request, long line, long loan) {}

    protected Formed loanOfDave(LocalDate from, LocalDate to) throws Exception {
        long request = createDraft(as(bob));
        long line = personLine(as(bob), request, 1, from, to, statistics);
        submit(as(bob), request).andExpect(status().isOk());
        select(as(bob), request, line, world.dave().id()).andExpect(status().isOk());
        decide(as(oscar), approvalOf(line), "APPROVE", "").andExpect(status().isOk());
        detail(as(bob), request).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        long loan = jdbc.queryForObject("SELECT id FROM loan WHERE line_id = ?", Long.class, line);
        return new Formed(request, line, loan);
    }

    protected ResultActions endLoan(RequestPostProcessor caller, long loan, LocalDate newEnd) throws Exception {
        return mvc.perform(post("/api/loans/{id}/end", loan).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newEnd\": \"" + newEnd + "\"}"));
    }

    protected ResultActions statement(String manager, long team) throws Exception {
        return mvc.perform(get("/api/ledger/statements/{team}/{period}", team, period).with(as(manager)));
    }

    protected ResultActions myProfile(String member) throws Exception {
        return mvc.perform(get("/api/members/me/profile").with(as(member)));
    }

    protected long originalEarning(long loan) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE loan_id = ? AND corrects_id IS NULL", Long.class,
                loan);
    }

    /** The Team's Earnings, corrections included, oldest first. */
    protected List<BigDecimal> earnings(long team) {
        return jdbc.queryForList("SELECT amount FROM transaction WHERE team_id = ? AND kind = 'EARNING' ORDER BY id",
                BigDecimal.class, team);
    }

    protected BigDecimal balance(long team) {
        return ledger.account(new TeamId(team)).balance().amount();
    }
}
