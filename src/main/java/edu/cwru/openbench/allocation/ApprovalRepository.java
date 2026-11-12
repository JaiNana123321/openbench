package edu.cwru.openbench.allocation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ApprovalRepository extends JpaRepository<ApprovalRow, Long> {

    List<ApprovalRow> findByCandidateIdIn(Collection<Long> candidateIds);

    List<ApprovalRow> findByApproverMemberIdAndStateOrderById(Long approver, ApprovalState state);

    /** The Requests with an Outstanding approval that is not escalated, by id. */
    @Query("""
            select distinct c.requestId from ApprovalRow a, CandidateRow c
            where a.candidateId = c.id and a.state = edu.cwru.openbench.allocation.ApprovalState.OUTSTANDING
              and a.escalated = false
            order by c.requestId""")
    List<Long> requestsWithUnescalatedOutstanding();
}
