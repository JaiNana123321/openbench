package edu.cwru.openbench.notifications;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<NotificationRow, Long> {

    /** The Member's unread notices, through the partial index on unread rows. */
    Page<NotificationRow> findByMemberIdAndReadAtIsNull(Long memberId, Pageable page);

    Optional<NotificationRow> findByIdAndMemberId(Long id, Long memberId);

    /** Sets {@code read_at} unless it is set already; returns the rows changed (0 or 1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NotificationRow n set n.readAt = :at where n.id = :id and n.readAt is null")
    int markRead(Long id, Instant at);
}
