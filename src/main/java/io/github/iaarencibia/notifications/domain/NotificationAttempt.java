package io.github.iaarencibia.notifications.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One dispatch attempt, as it happened. Kept outside the aggregate so the worker does not load the
 * whole history every time it claims a row.
 *
 * @param errorMessage {@code null} when it succeeded, required on failure
 * @param finishedAt never before {@code startedAt}
 * @param durationMs measured on a monotonic clock, so it need not match the gap between the two
 *     instants, which a wall-clock step widens or shrinks
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
        Instant finishedAt,
        long durationMs) {

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
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        // Mirrors ck_attempt_failure_has_reason: the row outlives the object that created
        // it, and a reader asking why a notification failed has nothing else to go on.
        if (outcome != AttemptOutcome.SUCCESS && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("a failed attempt must record why it failed");
        }
    }
}
