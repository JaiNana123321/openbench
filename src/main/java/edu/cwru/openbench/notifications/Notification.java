package edu.cwru.openbench.notifications;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotificationId;
import java.time.Instant;

/**
 * An in-app notice to one Member. {@code text} is complete as written; {@code link} is a Web client
 * route such as {@code /requests/42}, or null; {@code readAt} is null while unread. JSON:
 * {@code {"id": 7, "recipient": 3, "kind": "REQUEST_UPDATE", "text": "...", "link": "/requests/42",
 * "createdAt": "...", "readAt": null}}.
 */
public record Notification(
        NotificationId id,
        MemberId recipient,
        NotificationKind kind,
        String text,
        String link,
        Instant createdAt,
        Instant readAt) {}
