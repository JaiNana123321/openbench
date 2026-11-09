package edu.cwru.openbench.notifications;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.NotificationId;
import edu.cwru.openbench.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Notifications endpoints (design Table "Endpoints by module"): the caller's own notices only. */
@RestController
@RequestMapping("/api/notifications")
class NotificationsController {

    private final NotificationService notifications;

    NotificationsController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    PageResponse<Notification> unread(
            Actor actor,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(notifications.unread(actor, page, size));
    }

    @PostMapping("/{id}/read")
    Notification markRead(Actor actor, @PathVariable NotificationId id) {
        return notifications.markRead(actor, id);
    }
}
