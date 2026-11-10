package edu.cwru.openbench.notifications;

import edu.cwru.openbench.common.MemberId;

/**
 * How another module tells a Member that something concerning them happened (design Section 4.4).
 * The caller decides when a notice is due and what it says, and calls {@link #notify} inside its own
 * transaction: the notice exists exactly when the caller's action commits.
 */
public interface Notifier {

    /**
     * Writes the notice in the caller's transaction, then hands it to every {@link Delivery}.
     *
     * @param to the recipient; a caller that addresses a role resolves its holders first
     * @param text the complete sentence the Member reads
     * @param link a Web client route such as {@code /requests/42}, or null
     */
    void notify(MemberId to, NotificationKind kind, String text, String link);
}
