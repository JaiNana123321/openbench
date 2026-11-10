package edu.cwru.openbench.notifications;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotificationId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A row of {@code notification}. Never deleted (V60 revokes DELETE); only {@code readAt} changes,
 * once.
 */
@Entity
@Table(name = "notification")
class NotificationRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;

    @Enumerated(EnumType.STRING)
    private NotificationKind kind;

    private String text;

    private String link;

    private Instant createdAt;

    private Instant readAt;

    protected NotificationRow() {}

    NotificationRow(Long memberId, NotificationKind kind, String text, String link, Instant createdAt) {
        this.memberId = memberId;
        this.kind = kind;
        this.text = text;
        this.link = link;
        this.createdAt = createdAt;
    }

    Notification toNotification() {
        return new Notification(new NotificationId(id), new MemberId(memberId), kind, text, link, createdAt, readAt);
    }
}
