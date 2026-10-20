package edu.cwru.openbench.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PeriodCloseRepository extends JpaRepository<PeriodClose, PeriodClose.Key> {

    List<PeriodClose> findByTeamId(Long teamId);
}
