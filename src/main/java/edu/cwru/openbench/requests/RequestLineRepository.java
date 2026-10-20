package edu.cwru.openbench.requests;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface RequestLineRepository extends JpaRepository<RequestLineRow, Long> {

    /** The Request's lines in the order they were added. */
    List<RequestLineRow> findByRequestIdOrderById(Long requestId);

    /** The lines of several Requests, for a list page. */
    List<RequestLineRow> findByRequestIdInOrderById(Collection<Long> requestIds);
}
