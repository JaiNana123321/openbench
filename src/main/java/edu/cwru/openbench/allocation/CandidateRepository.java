package edu.cwru.openbench.allocation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CandidateRepository extends JpaRepository<CandidateRow, Long> {

    List<CandidateRow> findByLineIdOrderById(Long lineId);

    List<CandidateRow> findByRequestIdOrderById(Long requestId);
}
