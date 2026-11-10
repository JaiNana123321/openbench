package edu.cwru.openbench.notifications;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.NotificationId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (design Section 4.4): the {@link Notifier} other modules call, and the
 * design's {@code NotificationQueries}, the signed-in Member's unread notices and marking one read.
 *
 * <p>A notice is visible only to its recipient: another Member's notice is not found, which does
 * not reveal that it exists. {@code read_at} is set at most once, and no notice is deleted.
 */
@Service
@Transactional(readOnly = true)
public class NotificationService implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository notifications;
    private final List<Delivery> deliveries;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, List<Delivery> deliveries, Clock clock) {
        this.notifications = notifications;
        this.deliveries = List.copyOf(deliveries);
        this.clock = clock;
    }

    /** (Unscoped) Joins the caller's transaction: if the caller's action rolls back, so does the notice. */
    @Override
    @Transactional
    public void notify(MemberId to, NotificationKind kind, String text, String link) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A notification needs its text.");
        }
        Notification written = notifications.save(new NotificationRow(to.value(), kind, text, link, clock.instant()))
                .toNotification();
        log.info("event=notification id={} to={} kind={}", written.id().value(), to.value(), kind);
        deliveries.forEach(delivery -> delivery.deliver(written));
    }

    /** GET /api/notifications: the caller's unread notices, newest first. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public Page<Notification> unread(Actor actor, int page, int size) {
        return notifications.findByMemberIdAndReadAtIsNull(actor.memberId().value(), PageRequest.of(page, size, NEWEST_FIRST))
                .map(NotificationRow::toNotification);
    }

    /**
     * POST /api/notifications/{id}/read: marks the caller's own notice read and returns it. Marking
     * it again keeps the first {@code readAt}.
     *
     * @throws NotFoundException for another Member's notice, as for one that does not exist
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    @Transactional
    public Notification markRead(Actor actor, NotificationId id) {
        Long me = actor.memberId().value();
        notifications.findByIdAndMemberId(id.value(), me).orElseThrow(() -> notFound(id));
        notifications.markRead(id.value(), clock.instant());
        return notifications.findByIdAndMemberId(id.value(), me).orElseThrow(() -> notFound(id)).toNotification();
    }

    private static NotFoundException notFound(NotificationId id) {
        return new NotFoundException("Notification " + id.value() + " does not exist.");
    }
}
