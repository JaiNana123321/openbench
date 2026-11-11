package edu.cwru.openbench.allocation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface LoanRepository extends JpaRepository<LoanRow, Long> {

    /** The Members among these with a Loan overlapping any day of {@code [from, to]}. */
    @Query("""
            select distinct l.memberId from LoanRow l
            where l.memberId in :members and l.startsOn <= :to and l.endsOn >= :from""")
    List<Long> membersLentDuring(Collection<Long> members, LocalDate from, LocalDate to);

    /** The Loans of these Members overlapping any day of {@code [from, to]}. */
    @Query("""
            select l from LoanRow l
            where l.memberId in :members and l.startsOn <= :to and l.endsOn >= :from""")
    List<LoanRow> loansDuring(Collection<Long> members, LocalDate from, LocalDate to);
}
