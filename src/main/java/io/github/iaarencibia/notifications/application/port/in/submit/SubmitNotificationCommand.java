package io.github.iaarencibia.notifications.application.port.in.submit;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.NotificationPayload;

/**
 * A request to send a notification, already translated into domain terms by the adapter.
 *
 * @param payload what to send, and to whom
 * @param correlationId groups this notification with the rest of its operation; never null
 * @param idempotencyKey the caller's own key, or {@code null} when it sent none
 */
public record SubmitNotificationCommand(
        NotificationPayload payload, CorrelationId correlationId, String idempotencyKey) {}
