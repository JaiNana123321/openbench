package edu.cwru.openbench.requests;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SuggestionRepository extends JpaRepository<SuggestionRow, Long> {
    Page<SuggestionRow> findByMemberIdOrderByCreatedAtDescIdDesc(Long member, Pageable page);

    @Query("select s from SuggestionRow s where s.teamId = :team and s.managerId = :manager "
            + "order by case when s.state = 'OPEN' then 0 else 1 end, s.createdAt desc, s.id desc")
    Page<SuggestionRow> forManager(Long team, Long manager, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SuggestionRow s where s.id = :id and s.teamId = :team and s.managerId = :manager")
    Optional<SuggestionRow> lockForManager(Long id, Long team, Long manager);

    @Query("select s from SuggestionRow s, RequestLineRow l where s.acceptedLineId = l.id "
            + "and l.requestId = :request order by s.id")
    List<SuggestionRow> forRequest(Long request);
}
