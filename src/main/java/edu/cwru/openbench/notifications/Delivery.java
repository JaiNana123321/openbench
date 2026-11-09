package edu.cwru.openbench.notifications;

/**
 * A channel a written notice is handed to. Release 1 has only {@link InAppDelivery}, for which the
 * row is the delivery; an email channel (Stretch) would be a second implementation sending after
 * commit, and no caller of {@link Notifier} would change.
 */
public interface Delivery {

    void deliver(Notification notification);
}
