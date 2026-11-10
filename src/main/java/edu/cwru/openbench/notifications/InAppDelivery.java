package edu.cwru.openbench.notifications;

import org.springframework.stereotype.Component;

/** The in-app channel: the {@code notification} row is the delivery, so nothing more happens. */
@Component
class InAppDelivery implements Delivery {

    @Override
    public void deliver(Notification notification) {
        // The row written by NotificationService is what the Member's notification list shows.
    }
}
