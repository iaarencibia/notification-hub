package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;
import java.util.Optional;

/** What the application needs from storage, said in the language of the domain. */
public interface NotificationRepository {

    /**
     * The row is the queue. Reports a taken key by throwing rather than checking first.
     *
     * @throws IdempotencyKeyAlreadyUsedException if a notification already holds that key
     */
    void save(Notification notification);

    /**
     * @param idempotencyKey never {@code null}
     * @return the notification that key already created, or empty if it is unused
     */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);
}
