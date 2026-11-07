package edu.cwru.openbench.testcases;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.testcases.TestCaseReport.Outcome;
import edu.cwru.openbench.testcases.TestCaseReport.Status;
import edu.cwru.openbench.testcases.TestCaseReport.TestRun;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The run report's status rules and its tables, on a two-area document. */
class TestCaseReportTest {

    private static final TestRun PASSED = new TestRun("ATest", "passes", false, Outcome.PASSED, 1, null);
    private static final TestRun PASSED_PART = new TestRun("ATest", "passes a part", true, Outcome.PASSED, 1, null);
    private static final TestRun FAILED = new TestRun("ATest", "fails", false, Outcome.FAILED, 1, "expected 0.00");
    private static final TestRun DISABLED = new TestRun("ATest", "waits", false, Outcome.SKIPPED, 1, "drf-x: not built");

    @Test
    @DisplayName("A failure wins; then a whole-case pass; then a partial pass; a disabled test alone automates nothing")
    void decidesTheStatus() {
        assertThat(TestCaseReport.status(List.of(PASSED, FAILED))).isEqualTo(Status.FAILED);
        assertThat(TestCaseReport.status(List.of(PASSED_PART, PASSED))).isEqualTo(Status.PASSED);
        assertThat(TestCaseReport.status(List.of(PASSED_PART, DISABLED))).isEqualTo(Status.PASSED_PARTIAL);
        assertThat(TestCaseReport.status(List.of(DISABLED))).isEqualTo(Status.NOT_AUTOMATED);
        assertThat(TestCaseReport.status(List.of())).isEqualTo(Status.NOT_AUTOMATED);
    }

    @Test
    @DisplayName("The summary counts by status and priority, each area has its table, and each requirement its row")
    void rendersTheTables() {
        TestCaseDocument document = TestCaseDocument.parse(List.of("""
                \\subsection{Draft and Submit a Request}
                \\tcarea{DRF}
                \\begin{testcase}{drf-a}{Accepted at the boundary}{Draft.Quote.Line}
                \\tcfield{Priority}{High}
                \\end{testcase}
                \\begin{testcase}{drf-b}{Options | pipes}{Draft.Line.Add}
                \\tcfield{Priority}{Medium}
                \\end{testcase}
                """, """
                \\subsection{Ledger}
                \\tcarea{LED}
                \\begin{testcase}{led-a}{Balance}{Ledger.Account}
                \\tcfield{Priority}{High}
                \\end{testcase}
                """), """
                \\subsection{Draft and Submit a Request}
                \\fr{Draft.Quote.Line}{...}
                \\fr{Draft.Line.Add}{...}
                \\subsection{Ledger}
                \\fr{Ledger.Account}{...}
                \\fr[Stretch]{Ledger.Surcharge}{...}
                """);

        String report = TestCaseReport.render(document,
                Map.of("drf-a", List.of(PASSED), "drf-b", List.of(PASSED_PART, DISABLED), "led-a", List.of(FAILED)), "`abc123`");

        assertThat(report)
                .contains("build `abc123`")
                .contains("3 test cases: 1 Passed, 1 Passed (partial), 1 Failed, 0 Not automated.")
                .contains("| High | 2 | 1 | 0 | 1 | 0 |")
                .contains("| Medium | 1 | 0 | 1 | 0 | 0 |")
                .contains("| **All** | 3 | 1 | 1 | 1 | 0 |")
                .contains("Tagged tests in this run: 4 (by method, or by class where the class is tagged), 1 failed, 1 disabled.")
                .contains("## DRF: Draft and Submit a Request")
                .contains("| TC-DRF-01 | `drf-a` | Accepted at the boundary | High | Draft.Quote.Line | Passed | `ATest`: passes |")
                .contains("| TC-DRF-02 | `drf-b` | Options \\| pipes | Medium | Draft.Line.Add | Passed (partial) | "
                        + "`ATest`: passes a part [part]; `ATest`: waits (disabled: drf-x: not built) |")
                .contains("| TC-LED-01 | `led-a` | Balance | High | Ledger.Account | Failed | `ATest`: fails **FAILED** |")
                .contains("| Draft.Line.Add | Draft and Submit a Request | Release 1 | TC-DRF-02 (Passed (partial)) | Passed (partial) |")
                .contains("| Ledger.Surcharge | Ledger | Stretch | Not covered | Not automated |");
        assertThat(report.indexOf("## DRF")).isLessThan(report.indexOf("## LED"));
    }

    @Test
    @DisplayName("A class tag reads as one entry for all of the class's tests")
    void labelsAClassTag() {
        assertThat(new TestRun("InventorySecurityTest", null, false, Outcome.PASSED, 5, null).label())
                .isEqualTo("`InventorySecurityTest` (all 5 tests)");
    }
}
