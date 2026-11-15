package edu.cwru.openbench.allocation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CandidateRepository extends JpaRepository<CandidateRow, Long> {

    List<CandidateRow> findByLineIdOrderById(Long lineId);

    List<CandidateRow> findByRequestIdOrderById(Long requestId);

    /**
     * The Members selected, with an approval in one of these states, on another line of the Request
     * whose days overlap {@code from} to {@code to}, both included.
     */
    @Query("""
            select distinct c.memberId from CandidateRow c, ApprovalRow a
            where a.candidateId = c.id and not c.retired and c.requestId = :request and c.lineId <> :line and a.state in :states
              and c.startsOn <= :to and c.endsOn >= :from""")
    List<Long> selectedOnOverlappingLines(Long request, Long line, LocalDate from, LocalDate to,
            Collection<ApprovalState> states);
}
