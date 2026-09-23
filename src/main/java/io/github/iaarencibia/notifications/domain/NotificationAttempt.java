package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One dispatch attempt, as it happened. Kept outside the aggregate so the worker does not load the
 * whole history every time it claims a row.
 *
 * @param errorMessage {@code null} when it succeeded, required on failure
 * @param finishedAt never before {@code startedAt}
 */
public record NotificationAttempt(
        UUID id,
        UUID notificationId,
        int attemptNumber,
        Channel channel,
        AttemptOutcome outcome,
        Integer responseCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    public NotificationAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        // Mirrors ck_attempt_failure_has_reason: the row outlives the object that created
        // it, and a reader asking why a notification failed has nothing else to go on.
        if (outcome != AttemptOutcome.SUCCESS && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("a failed attempt must record why it failed");
        }
    }

    /**
     * Derived rather than stored as a component, so it cannot disagree with the two instants it is
     * measured from.
     */
    public long durationMs() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
