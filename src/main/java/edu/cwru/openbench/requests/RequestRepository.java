package edu.cwru.openbench.requests;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RequestRepository extends JpaRepository<RequestRow, Long> {

    /**
     * {@code SELECT ... FOR UPDATE}: the Request, locked until the transaction ends, so a line edit
     * and a transition of the same Request run one after the other and the edit sees the new state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequestRow r where r.id = :id")
    Optional<RequestRow> lockById(Long id);

    Page<RequestRow> findByTeamId(Long teamId, Pageable page);

    Page<RequestRow> findByTeamIdAndState(Long teamId, RequestState state, Pageable page);

    Page<RequestRow> findByState(RequestState state, Pageable page);
}
