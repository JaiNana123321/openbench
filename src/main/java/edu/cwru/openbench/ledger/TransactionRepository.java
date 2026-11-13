package edu.cwru.openbench.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByTeamIdOrderByPostedAtAscIdAsc(Long teamId);

    /** The Team's Transactions posted into any of the Periods: for the rollover, those of the starting Period or later. */
    List<Transaction> findByTeamIdAndPeriodIdIn(Long teamId, Collection<Long> periodIds);

    /** The line's Transactions of one kind, oldest first: its Charge, or its Refunds. */
    List<Transaction> findByLineIdAndKindOrderByIdAsc(Long lineId, TransactionKind kind);

    /** Whether the Loan's Earning has posted: an EARNING naming the Loan and correcting nothing. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM transaction
                           WHERE loan_id = :loan AND kind = 'EARNING' AND corrects_id IS NULL)""", nativeQuery = true)
    boolean earningPostedFor(Long loan);

    /** The Loan's Earnings, oldest first: its original Earning, then its corrections. */
    List<Transaction> findByLoanIdAndKindOrderByIdAsc(Long loanId, TransactionKind kind);
}
