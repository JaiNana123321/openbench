package edu.cwru.openbench.requests;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RequestEventRepository extends JpaRepository<RequestEventRow, Long> {

    /** The Request's history, oldest first. */
    List<RequestEventRow> findByRequestIdOrderByAtAscIdAsc(Long requestId);
}
