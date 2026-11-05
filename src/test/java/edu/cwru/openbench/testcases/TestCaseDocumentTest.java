package edu.cwru.openbench.testcases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import edu.cwru.openbench.testcases.TestCaseDocument.Area;
import edu.cwru.openbench.testcases.TestCaseDocument.Requirement;
import edu.cwru.openbench.testcases.TestCaseDocument.TestCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the parsing of the test case document's LaTeX, which the run report and the key check rely on. */
class TestCaseDocumentTest {

    private static final String SRS = """
            % \\fr{Commented.Out}{not a requirement}
            \\subsection{Inventory and Resource Types}\\label{feat:inventory}
            \\fr{Inventory.Type.Define}{The system shall let an Administrator define a type as \\FR{Other.Id} says {nested}.}
            \\subsection{Metered Allocation}
            \\fr[Stretch]{Metered.Tier.Define}{The system shall let the Capacity Planner define tiers.}
            """;

    @Test
    @DisplayName("A case header gives key, title, and requirements; the Priority field and the area give the rest")
    void parsesACase() {
        TestCaseDocument document = TestCaseDocument.parse(List.of("""
                \\subsection{Draft and Submit a Request}\\label{cases:draft}
                \\tcarea{DRF}
                \\begin{testcase}{drf-create}{A Manager creates a Draft for \\code{their} own Team}{Draft.Create,  Lifecycle.States}
                \\tcfield{Actor}{\\code{alice} (Manager, Platform)}
                \\tcfield{Priority}{High}
                \\end{testcase}
                """), SRS);

        assertThat(document.areas()).containsExactly(new Area("DRF", "Draft and Submit a Request"));
        assertThat(document.cases()).containsExactly(new TestCase("DRF", 1, "drf-create",
                "A Manager creates a Draft for their own Team", List.of("Draft.Create", "Lifecycle.States"), "High"));
        assertThat(document.cases().getFirst().id()).isEqualTo("TC-DRF-01");
    }

    @Test
    @DisplayName("Cases are numbered in file order within each area; a commented-out case does not count")
    void numbersCasesWithinTheirArea() {
        TestCaseDocument document = TestCaseDocument.parse(List.of("""
                \\subsection{Inventory}
                \\tcarea{INV}
                \\begin{testcase}{inv-a}{A}{X}
                \\tcfield{Priority}{High}
                \\end{testcase}
                % \\begin{testcase}{inv-old}{Old}{X}
                \\begin{testcase}{inv-b}{B}{X}
                \\tcfield{Priority}{Medium}
                \\end{testcase}
                """, """
                \\subsection{Release and Refund}
                \\tcarea{REL}
                \\begin{testcase}{rel-a}{Release seven or more days ahead for a 75\\% Refund}{Y}
                \\tcfield{Priority}{Low}
                \\end{testcase}
                """), SRS);

        assertThat(document.cases()).extracting(TestCase::id, TestCase::key, TestCase::priority).containsExactly(
                tuple("TC-INV-01", "inv-a", "High"),
                tuple("TC-INV-02", "inv-b", "Medium"),
                tuple("TC-REL-01", "rel-a", "Low"));
        assertThat(document.cases().getLast().title()).isEqualTo("Release seven or more days ahead for a 75% Refund");
        assertThat(document.keys()).containsExactlyInAnyOrder("inv-a", "inv-b", "rel-a");
    }

    @Test
    @DisplayName("Nested braces in a title are kept whole, and the Priority field is read from the case's own body")
    void readsNestedBracesAndTheOwnPriority() {
        TestCaseDocument document = TestCaseDocument.parse(List.of("""
                \\tcarea{LED}
                \\begin{testcase}{led-a}{Quotes follow the formula \\textbf{\\code{with}} bands}{Ledger.Price.Formula}
                \\tcfield{Pass criteria}{\\code{Priority} is not a field here}
                \\tcfield{Priority}{Medium}
                \\end{testcase}
                \\begin{testcase}{led-b}{Next}{Ledger.Account}
                \\tcfield{Priority}{High}
                \\end{testcase}
                """), SRS);

        assertThat(document.cases()).extracting(TestCase::title, TestCase::priority)
                .containsExactly(tuple("Quotes follow the formula with bands", "Medium"),
                        tuple("Next", "High"));
    }

    @Test
    @DisplayName("A case without a Priority field is an error, not a silent gap in the report")
    void refusesACaseWithoutPriority() {
        assertThatThrownBy(() -> TestCaseDocument.parse(List.of("""
                \\tcarea{LED}
                \\begin{testcase}{led-a}{A}{Ledger.Account}
                \\tcfield{Actor}{erin}
                \\end{testcase}
                \\begin{testcase}{led-b}{B}{Ledger.Account}
                \\tcfield{Priority}{High}
                \\end{testcase}
                """), SRS)).hasMessageContaining("led-a").hasMessageContaining("Priority");
    }

    @Test
    @DisplayName("The \\\\fr lines give each requirement with its feature and whether it is Stretch")
    void parsesTheRequirements() {
        TestCaseDocument document = TestCaseDocument.parse(List.of(), SRS);

        assertThat(document.requirements()).containsExactly(
                new Requirement("Inventory and Resource Types", "Inventory.Type.Define", false),
                new Requirement("Metered Allocation", "Metered.Tier.Define", true));
    }

    @Test
    @DisplayName("The document in docs/: 91 cases in 12 areas, unique keys, and 47 requirements, as gen_trace.py counts")
    void readsTheRealDocument() {
        TestCaseDocument document = TestCaseDocument.load();

        assertThat(document.cases()).hasSize(91);
        assertThat(document.keys()).hasSize(91);
        assertThat(document.areas()).extracting(Area::code).containsExactly(
                "INV", "PRF", "SUG", "DRF", "MAT", "EXC", "MET", "LIF", "REL", "LED", "DSH", "ADM");
        assertThat(document.cases()).extracting(TestCase::priority).containsOnly("High", "Medium", "Low");
        assertThat(document.cases().stream().filter(c -> c.priority().equals("High"))).hasSize(63);
        assertThat(document.cases()).filteredOn(c -> c.key().equals("drf-balance-boundary")).singleElement()
                .satisfies(c -> {
                    assertThat(c.id()).isEqualTo("TC-DRF-08");
                    assertThat(c.title()).isEqualTo("A Required sum equal to the Available Balance is accepted");
                    assertThat(c.requirements()).containsExactly("Draft.Quote.Line", "Ledger.Grant.Period");
                    assertThat(c.priority()).isEqualTo("Medium");
                });
        assertThat(document.requirements()).hasSize(47);
        assertThat(document.requirements()).filteredOn(Requirement::stretch).hasSize(6);
    }
}
