package io.github.iaarencibia.notifications.application.port.in.submit;

import java.util.UUID;

/** Accepting a notification for delivery: the only entry point a caller drives. */
public interface SubmitNotificationUseCase {

    /**
     * Idempotent: a repeated key returns the identifier the first call created instead of failing.
     *
     * @return the identifier of the notification
     */
    UUID submit(SubmitNotificationCommand command);
}
