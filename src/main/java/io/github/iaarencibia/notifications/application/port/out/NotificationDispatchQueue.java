package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import java.util.List;

/**
 * The queue, from the dispatcher's side: take work, then report what came of it.
 *
 * <p>Two calls, not one: the network call happens between them, holding no database connection.
 */
public interface NotificationDispatchQueue {

    /** Highest priority first, then oldest due; skips rows another instance already claims. */
    List<ClaimedNotification> claimDue(int batchSize, String claimedBy);

    /**
     * One transaction: the attempt and the resulting state transition.
     *
     * @throws ClaimLostException if the notification moved on since it was claimed
     */
    void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt);
}
