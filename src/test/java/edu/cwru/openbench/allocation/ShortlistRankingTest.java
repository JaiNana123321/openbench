package edu.cwru.openbench.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Shortlist's filter and order, without Spring (design Section 4.5, MatchedAllocator worked example). */
@DisplayName("Matched.Shortlist.Build: ShortlistRanking")
class ShortlistRankingTest {

    private static final DateRange OCTOBER = new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    private static DateRange days(int fromMonth, int fromDay, int toMonth, int toDay) {
        return new DateRange(LocalDate.of(2026, fromMonth, fromDay), LocalDate.of(2026, toMonth, toDay));
    }

    @Test
    @DisplayName("the design's worked example: Diaz, Baptiste, Chen, Abe by October person-days, then name")
    void ranksTheWorkedExample() {
        // A line for Monday, October 12 to Friday, November 20. Ford is lent November 2 to 6, inside
        // the window; Gupta is available only to November 13, so Members never offers him.
        List<ShortlistRanking.Person> people = List.of(
                person(1, "Abe", false, ShortlistRanking.personDaysLent(List.of(days(9, 28, 10, 9)), OCTOBER)),
                person(2, "Baptiste", false, ShortlistRanking.personDaysLent(List.of(days(10, 5, 10, 7)), OCTOBER)),
                person(3, "Chen", false, ShortlistRanking.personDaysLent(List.of(days(10, 1, 10, 5)), OCTOBER)),
                person(4, "Diaz", false, ShortlistRanking.personDaysLent(List.of(), OCTOBER)),
                person(5, "Ford", true, ShortlistRanking.personDaysLent(List.of(days(11, 2, 11, 6)), OCTOBER)));

        List<ShortlistRanking.Person> ranked = ShortlistRanking.rank(people);

        assertThat(ranked).extracting(ShortlistRanking.Person::name).containsExactly("Diaz", "Baptiste", "Chen", "Abe");
        assertThat(ranked).extracting(ShortlistRanking.Person::personDaysLent).containsExactly(0, 3, 3, 7);
    }

    @Test
    @DisplayName("person-days lent count only the weekdays of Loans inside the Period")
    void countsWeekdaysInsideThePeriod() {
        // Abe: September 28 to October 9; in October Thursday 1, Friday 2, and Monday 5 to Friday 9.
        assertThat(ShortlistRanking.personDaysLent(List.of(days(9, 28, 10, 9)), OCTOBER)).isEqualTo(7);
        // Chen: Thursday October 1 to Monday October 5, over a weekend: three weekdays.
        assertThat(ShortlistRanking.personDaysLent(List.of(days(10, 1, 10, 5)), OCTOBER)).isEqualTo(3);
        // A Loan in November lends nothing in October; two Loans add up.
        assertThat(ShortlistRanking.personDaysLent(List.of(days(11, 2, 11, 6)), OCTOBER)).isZero();
        assertThat(ShortlistRanking.personDaysLent(List.of(days(10, 5, 10, 6), days(10, 12, 10, 12)), OCTOBER))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the system selects no one: Members not eligible by profile or lent during the window are left out")
    void leavesOutTheIneligible() {
        List<ShortlistRanking.Person> ranked = ShortlistRanking.rank(List.of(
                new ShortlistRanking.Person(new MemberId(1), "Inactive", false, false, 0),
                new ShortlistRanking.Person(new MemberId(2), "Lent", true, true, 0),
                new ShortlistRanking.Person(new MemberId(3), "Free", true, false, 5)));

        assertThat(ranked).extracting(ShortlistRanking.Person::name).containsExactly("Free");
    }

    @Test
    @DisplayName("equal names are ordered by id, so the order is stable")
    void tiesByIdAfterName() {
        List<ShortlistRanking.Person> ranked = ShortlistRanking.rank(List.of(
                person(9, "Dave Kim", false, 0), person(4, "Dave Kim", false, 0)));

        assertThat(ranked).extracting(person -> person.id().value()).containsExactly(4L, 9L);
    }

    private static ShortlistRanking.Person person(long id, String name, boolean lent, int personDays) {
        return new ShortlistRanking.Person(new MemberId(id), name, true, lent, personDays);
    }
}
