package edu.cwru.openbench.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByTeamIdOrderByPostedAtAscIdAsc(Long teamId);

    /** The line's Transactions of one kind, oldest first: its Charge, or its Refunds. */
    List<Transaction> findByLineIdAndKindOrderByIdAsc(Long lineId, TransactionKind kind);
}
