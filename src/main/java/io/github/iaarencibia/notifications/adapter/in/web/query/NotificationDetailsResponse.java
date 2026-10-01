package io.github.iaarencibia.notifications.adapter.in.web.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.iaarencibia.notifications.application.port.in.query.NotificationDetails;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
record NotificationDetailsResponse(
        UUID id,
        Channel channel,
        String recipient,
        NotificationStatus status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        String correlationId,
        List<Attempt> history) {

    static NotificationDetailsResponse from(NotificationDetails details) {
        Notification notification = details.notification();
        return new NotificationDetailsResponse(
                notification.id(),
                notification.payload().channel(),
                notification.payload().recipient(),
                notification.status(),
                notification.attempts(),
                notification.maxAttempts(),
                notification.nextAttemptAt(),
                notification.lastError(),
                notification.createdAt(),
                notification.updatedAt(),
                notification.sentAt(),
                notification.correlationId().value(),
                details.attempts().stream().map(Attempt::from).toList());
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Attempt(
            int number, Instant at, AttemptOutcome outcome, Integer statusCode, String error) {

        static Attempt from(NotificationAttempt attempt) {
            return new Attempt(
                    attempt.attemptNumber(),
                    attempt.startedAt(),
                    attempt.outcome(),
                    attempt.responseCode(),
                    attempt.errorMessage());
        }
    }
}
