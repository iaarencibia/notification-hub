package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;
import java.util.Objects;

/**
 * A notification this instance owns, together with the row version it owned it at.
 *
 * <p>The version is an optimistic-locking token: storage refuses to write back if the row moved.
 */
public record ClaimedNotification(Notification notification, long version) {

    public ClaimedNotification {
        Objects.requireNonNull(notification, "notification must not be null");
    }
}
