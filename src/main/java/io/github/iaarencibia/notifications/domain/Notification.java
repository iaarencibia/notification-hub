package io.github.iaarencibia.notifications.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A notification and where it stands in its lifecycle. Owns the legal transitions, the attempt
 * budget, and the record of every attempt. The claim query, not this class, decides whether a
 * notification is due; it computes {@code nextAttemptAt} but does not enforce it.
 */
public final class Notification {

    /** Mirrors the width of {@code notification.idempotency_key}. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    /** Mirrors the width of {@code notification.claimed_by}. */
    public static final int MAX_CLAIMED_BY_LENGTH = 128;

    private final UUID id;
    private final NotificationPayload payload;
    private final CorrelationId correlationId;
    private final String idempotencyKey;
    private final int maxAttempts;
    private final Instant createdAt;

    private NotificationStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant claimedAt;
    private String claimedBy;
    private Instant updatedAt;
    private Instant sentAt;

    private Notification(
            UUID id,
            NotificationPayload payload,
            CorrelationId correlationId,
            String idempotencyKey,
            int maxAttempts,
            Instant now) {
        this.id = id;
        this.payload = payload;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
        this.createdAt = now;
        this.updatedAt = now;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    private Notification(
            UUID id,
            NotificationPayload payload,
            NotificationStatus status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            Instant claimedAt,
            String claimedBy,
            CorrelationId correlationId,
            String idempotencyKey,
            Instant createdAt,
            Instant updatedAt,
            Instant sentAt) {
        this.id = id;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.claimedAt = claimedAt;
        this.claimedBy = claimedBy;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sentAt = sentAt;
    }

    /** Creates a notification already queued: PENDING and due immediately. */
    public static Notification submit(
            UUID id,
            NotificationPayload payload,
            CorrelationId correlationId,
            String idempotencyKey,
            int maxAttempts,
            Instant now) {

        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (idempotencyKey != null) {
            // Blank is rejected rather than read as absent: an empty string is NOT NULL, so
            // the partial unique index would store it as a real key, turning every caller's
            // mistake into duplicate notifications.
            if (idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            // Counted in UTF-16 units, not characters: the key arrives in an HTTP header,
            // whose values are ASCII, so one unit is always one character here.
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "idempotencyKey must not exceed "
                                + MAX_IDEMPOTENCY_KEY_LENGTH
                                + " characters");
            }
        }

        return new Notification(id, payload, correlationId, idempotencyKey, maxAttempts, now);
    }

    /**
     * Rebuilds a notification that already exists, with the history it has accumulated. Unlike
     * {@link #submit}, this accepts any state the schema allows: what it refuses is exactly what
     * the {@code CHECK} constraints refuse.
     */
    public static Notification rehydrate(
            UUID id,
            NotificationPayload payload,
            NotificationStatus status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            Instant claimedAt,
            String claimedBy,
            CorrelationId correlationId,
            String idempotencyKey,
            Instant createdAt,
            Instant updatedAt,
            Instant sentAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // Mirrors ck_notification_max_attempts and ck_notification_attempts.
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (attempts < 0 || attempts > maxAttempts) {
            throw new IllegalArgumentException(
                    "attempts must be between 0 and " + maxAttempts + ", was " + attempts);
        }
        // Mirrors ck_notification_claim_consistency: a DISPATCHING row with no owner is
        // corrupt data the reaper could never trace back to the instance that abandoned it.
        if (status == NotificationStatus.DISPATCHING && (claimedAt == null || claimedBy == null)) {
            throw new IllegalArgumentException("a DISPATCHING notification must name its owner");
        }

        return new Notification(
                id,
                payload,
                status,
                attempts,
                maxAttempts,
                nextAttemptAt,
                lastError,
                claimedAt,
                claimedBy,
                correlationId,
                idempotencyKey,
                createdAt,
                updatedAt,
                sentAt);
    }

    /**
     * Takes ownership of the notification for dispatch. Does not consume an attempt: a claim the
     * reaper later reverses costs the notification nothing.
     */
    public void claim(Instant now, String claimedBy) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(claimedBy, "claimedBy must not be null");
        if (claimedBy.isBlank()) {
            // The reaper traces abandoned claims by this value; blank would be untraceable.
            throw new IllegalArgumentException("claimedBy must not be blank");
        }
        if (claimedBy.length() > MAX_CLAIMED_BY_LENGTH) {
            throw new IllegalArgumentException(
                    "claimedBy must not exceed " + MAX_CLAIMED_BY_LENGTH + " characters");
        }

        transitionTo(NotificationStatus.DISPATCHING, now);
        this.claimedAt = now;
        this.claimedBy = claimedBy;
    }

    /**
     * Applies the result of one delivery and returns the record of it. Single entry point:
     * splitting the state change from the record would let a caller do one without the other.
     */
    public NotificationAttempt recordAttempt(
            DispatchOutcome outcome,
            Instant startedAt,
            Instant finishedAt,
            RetryPolicy retryPolicy) {

        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
        if (status != NotificationStatus.DISPATCHING) {
            // A result for a row nobody owns means two instances dispatched it.
            throw new InvalidStateTransitionException(status, "record a dispatch attempt");
        }

        attempts++;
        NotificationAttempt attempt =
                new NotificationAttempt(
                        UUID.randomUUID(),
                        id,
                        attempts,
                        payload.channel(),
                        outcome.attemptOutcome(),
                        outcome.responseCode(),
                        outcome.errorMessage(),
                        startedAt,
                        finishedAt);

        switch (outcome) {
            case DispatchOutcome.Success success -> markSent(finishedAt);
            case DispatchOutcome.PermanentFailure failure -> markFailed(finishedAt, failure);
            case DispatchOutcome.RetryableFailure failure -> {
                if (attempts >= maxAttempts) {
                    markFailed(finishedAt, failure);
                } else {
                    reschedule(finishedAt, failure, retryPolicy);
                }
            }
        }

        return attempt;
    }

    private void markSent(Instant at) {
        transitionTo(NotificationStatus.SENT, at);
        this.sentAt = at;
        this.lastError = null;
        releaseClaim();
    }

    private void markFailed(Instant at, DispatchOutcome failure) {
        transitionTo(NotificationStatus.FAILED, at);
        this.lastError = failure.errorMessage();
        releaseClaim();
    }

    private void reschedule(
            Instant at, DispatchOutcome.RetryableFailure failure, RetryPolicy policy) {
        transitionTo(NotificationStatus.PENDING, at);
        this.lastError = failure.errorMessage();
        // Counted from when the attempt ended, not when it started: a destination that took
        // thirty seconds to time out has already had those thirty seconds of grace.
        this.nextAttemptAt = at.plus(policy.delayFor(attempts, failure.retryAfter()));
        releaseClaim();
    }

    /** The single place a status changes, so no move can bypass the lifecycle rules. */
    private void transitionTo(NotificationStatus target, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = at;
    }

    private void releaseClaim() {
        this.claimedAt = null;
        this.claimedBy = null;
    }

    public UUID id() {
        return id;
    }

    public NotificationPayload payload() {
        return payload;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    /**
     * @return the client's own key, or {@code null} when it supplied none
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    public NotificationStatus status() {
        return status;
    }

    /**
     * @return deliveries recorded; one reclaimed by the reaper before finishing never counts
     */
    public int attempts() {
        return attempts;
    }

    /**
     * @return the attempt budget, fixed at creation
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * @return the most recent failure, or {@code null}; full history is in {@code
     *     notification_attempt}
     */
    public String lastError() {
        return lastError;
    }

    /**
     * @return when the current claim was taken, or {@code null} outside DISPATCHING
     */
    public Instant claimedAt() {
        return claimedAt;
    }

    /**
     * @return the instance that owns the current claim, or {@code null} outside DISPATCHING
     */
    public String claimedBy() {
        return claimedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * @return when delivery succeeded, or {@code null} until it does
     */
    public Instant sentAt() {
        return sentAt;
    }
}
