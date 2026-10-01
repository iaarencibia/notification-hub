package io.github.iaarencibia.notifications.application.port.in.query;

import java.util.UUID;

/**
 * Reading a notification back with its attempts; an unknown id throws {@link
 * NotificationNotFoundException}.
 */
public interface GetNotificationUseCase {

    NotificationDetails get(UUID id);
}
