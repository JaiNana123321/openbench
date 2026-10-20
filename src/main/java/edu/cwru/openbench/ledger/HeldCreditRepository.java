package edu.cwru.openbench.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

interface HeldCreditRepository extends JpaRepository<HeldCredit, Long> {}
