package edu.cwru.openbench.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByTeamIdOrderByPostedAtAscIdAsc(Long teamId);

    /** The Team's Transactions posted into any of the Periods: for the rollover, those of the starting Period or later. */
    List<Transaction> findByTeamIdAndPeriodIdIn(Long teamId, Collection<Long> periodIds);

    /** The line's Transactions of one kind, oldest first: its Charge, or its Refunds. */
    List<Transaction> findByLineIdAndKindOrderByIdAsc(Long lineId, TransactionKind kind);
}
