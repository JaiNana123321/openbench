package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The Shortlist's eligibility filter and order, pure (design Section 4.5, "Pure parts"): a Member is
 * eligible when active, outside the requesting Team, available every day of the window, holding
 * every skill, and in no Loan overlapping it; eligible Members are ordered by person-days lent in the
 * current Period, then name, then id. Person-days lent are the Monday-to-Friday days of formed Loans
 * inside the Period.
 */
final class ShortlistRanking {

    private ShortlistRanking() {}

    /** What the filter needs to know about one Member, gathered by the caller. */
    record Person(MemberId id, String name, boolean eligibleByProfile, boolean lentDuringWindow, int personDaysLent) {}

    /** The eligible Members, best first: profile-eligible and not lent during the window. */
    static List<Person> rank(Collection<Person> people) {
        return people.stream()
                .filter(Person::eligibleByProfile)
                .filter(person -> !person.lentDuringWindow())
                .sorted(Comparator.comparingInt(Person::personDaysLent)
                        .thenComparing(Person::name)
                        .thenComparing(person -> person.id().value()))
                .toList();
    }

    /** The weekdays of the Loans that fall inside the Period. */
    static int personDaysLent(Collection<DateRange> loans, DateRange period) {
        return loans.stream()
                .map(loan -> loan.intersection(period))
                .flatMap(java.util.Optional::stream)
                .mapToInt(DateRange::weekdays)
                .sum();
    }
}
