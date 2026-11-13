package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.common.Role;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Requirement PE-shortlist (issue #21): building a Shortlist, and selecting from it, takes a fixed
 * number of SQL statements whatever the number of eligible Members, so 500 Members stay within the
 * 3 s budget. Counted with Hibernate's statistics, switched on around the one call measured. No test
 * case covers PE-shortlist, so this test verifies none.
 */
class ShortlistQueriesTest extends MatchedWorldTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("PE-shortlist: the Shortlist of 50 eligible Members takes as many statements as that of 5")
    void theShortlistTakesAFixedNumberOfStatements() throws Exception {
        Line five = lineWithEligible(5);
        Line fifty = lineWithEligible(50);

        long forFive = statements(() -> shortlist(as(alice), five.request(), five.id())
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates", hasSize(5))));
        long forFifty = statements(() -> shortlist(as(alice), fifty.request(), fifty.id())
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates", hasSize(50))));

        assertThat(forFifty).as("statements for 50 Candidates, against %d for 5", forFive).isEqualTo(forFive);
    }

    @Test
    @DisplayName("PE-shortlist: selecting from 50 eligible Members takes as many statements as from 5")
    void selectingTakesAFixedNumberOfStatements() throws Exception {
        Line five = lineWithEligible(5);
        Line fifty = lineWithEligible(50);

        long forFive = statements(() -> select(as(alice), five.request(), five.id(), five.eligible().getFirst())
                .andExpect(status().isOk()));
        long forFifty = statements(() -> select(as(alice), fifty.request(), fifty.id(), fifty.eligible().getFirst())
                .andExpect(status().isOk()));

        assertThat(forFifty).as("statements for 50 Candidates, against %d for 5", forFive).isEqualTo(forFive);
    }

    /** A Pending Request of Alice's with one Person line whose Shortlist holds exactly {@code count} Members. */
    private Line lineWithEligible(int count) throws Exception {
        LocalDate d = dayD();
        long skill = testData().skill(unique("Bulk skill"));
        long lenders = testData().team(unique("Lenders"));
        testData().member(lenders, unique("lenders-manager"), Role.MANAGER);
        List<Long> eligible = testData().lendableMembers(lenders, count, skill, LocalDate.now(clock), d.plusDays(60));
        long request = createDraft(as(alice));
        long line = personLine(as(alice), request, 1, d, d.plusDays(1), skill);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("PENDING"));
        return new Line(request, line, eligible);
    }

    private record Line(long request, long id, List<Long> eligible) {}

    /** The SQL statements Hibernate prepared during the call. */
    private long statements(Call call) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.clear();
        statistics.setStatisticsEnabled(true);
        try {
            call.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @FunctionalInterface
    private interface Call {
        ResultActions run() throws Exception;
    }
}
