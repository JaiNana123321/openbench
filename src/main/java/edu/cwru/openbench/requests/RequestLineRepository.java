package edu.cwru.openbench.requests;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RequestLineRepository extends JpaRepository<RequestLineRow, Long> {

    /** The Request's lines in the order they were added. */
    List<RequestLineRow> findByRequestIdOrderById(Long requestId);

    /** The lines of several Requests, for a list page. */
    List<RequestLineRow> findByRequestIdInOrderById(Collection<Long> requestIds);

    /** The line's Request without loading the line, so the line is read only after the Request lock. */
    @Query("select l.requestId from RequestLineRow l where l.id = :id")
    Optional<Long> findRequestIdById(Long id);
}
