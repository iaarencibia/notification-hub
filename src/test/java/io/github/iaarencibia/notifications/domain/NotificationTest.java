package io.github.iaarencibia.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Time and randomness are always arguments here, never ambient state, so assertions are exact. */
class NotificationTest {

    private static final UUID ID = UUID.fromString("3f2a1c40-0000-4000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-08-29T10:00:00Z");
    private static final String INSTANCE = "notification-service-3";
    private static final CorrelationId CORRELATION_ID = new CorrelationId("5c1b9e2a-trace");

    private static final int MAX_ATTEMPTS = 3;

    /**
     * Pinned to zero, not the jitter fraction, so the expected delays are exact; jitter is covered
     * by RetryPolicyTest.
     */
    private static final RetryPolicy POLICY =
            new RetryPolicy(Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2, () -> 0.0);

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "https://destination.test/hook",
                Channel.SERVICE,
                "Deployment finished",
                "Version 0.1.0 is live.",
                Priority.HIGH,
                Map.of("source", "ci"));
    }

    private static Notification submitted() {
        return submitted(MAX_ATTEMPTS);
    }

    private static Notification submitted(int maxAttempts) {
        return Notification.submit(ID, payload(), CORRELATION_ID, "idem-key-1", maxAttempts, T0);
    }

    private static NotificationAttempt failRetryably(Notification notification, Instant at) {
        notification.claim(at, INSTANCE);
        return notification.recordAttempt(
                new DispatchOutcome.RetryableFailure(503, "Service Unavailable", null),
                at,
                at.plusMillis(50),
                Duration.ofMillis(50),
                POLICY);
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        @DisplayName("starts PENDING and immediately claimable")
        void startsPendingAndClaimable() {
            Notification notification = submitted();

            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.attempts()).isZero();
            assertThat(notification.maxAttempts()).isEqualTo(3);

            // next_attempt_at = now means the very next poll picks it up. Any later value
            // would add latency that nothing asked for.
            assertThat(notification.nextAttemptAt()).isEqualTo(T0);
            assertThat(notification.createdAt()).isEqualTo(T0);
            assertThat(notification.updatedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("carries no dispatch history yet")
        void carriesNoHistoryYet() {
            Notification notification = submitted();

            assertThat(notification.sentAt()).isNull();
            assertThat(notification.lastError()).isNull();
            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("keeps the payload and the trace identifier it was submitted with")
        void keepsPayloadAndCorrelationId() {
            Notification notification = submitted();

            assertThat(notification.id()).isEqualTo(ID);
            assertThat(notification.payload()).isEqualTo(payload());
            assertThat(notification.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(notification.idempotencyKey()).isEqualTo("idem-key-1");
        }

        @Test
        @DisplayName("accepts a missing idempotency key")
        void acceptsMissingIdempotencyKey() {
            // Only the caller knows whether two requests are the same send; forcing a key would
            // make indifferent callers mint one per call, defeating the protection it appears to
            // give.
            Notification notification =
                    Notification.submit(ID, payload(), CORRELATION_ID, null, 3, T0);

            assertThat(notification.idempotencyKey()).isNull();
        }

        @Test
        @DisplayName("rejects a blank idempotency key, which is not the same as none")
        void rejectsBlankIdempotencyKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> Notification.submit(ID, payload(), CORRELATION_ID, "", 3, T0));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> Notification.submit(ID, payload(), CORRELATION_ID, "   ", 3, T0));
        }

        @Test
        @DisplayName("accepts an idempotency key at the column width and rejects one over")
        void idempotencyKeyBoundary() {
            Notification accepted =
                    Notification.submit(ID, payload(), CORRELATION_ID, "x".repeat(255), 3, T0);

            assertThat(accepted.idempotencyKey()).hasSize(255);
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    Notification.submit(
                                            ID, payload(), CORRELATION_ID, "x".repeat(256), 3, T0));
        }

        @Test
        @DisplayName("requires an attempt budget of at least one")
        void requiresAtLeastOneAttempt() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> Notification.submit(ID, payload(), CORRELATION_ID, null, 0, T0));
        }

        @Test
        @DisplayName("requires an id, a payload, a correlation id and a timestamp")
        void requiresMandatoryArguments() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    Notification.submit(
                                            null, payload(), CORRELATION_ID, null, 3, T0));
            assertThatNullPointerException()
                    .isThrownBy(() -> Notification.submit(ID, null, CORRELATION_ID, null, 3, T0));
            assertThatNullPointerException()
                    .isThrownBy(() -> Notification.submit(ID, payload(), null, null, 3, T0));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    Notification.submit(
                                            ID, payload(), CORRELATION_ID, null, 3, null));
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("moves to DISPATCHING and records who owns the work")
        void recordsOwnership() {
            Notification notification = submitted();

            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThat(notification.status()).isEqualTo(NotificationStatus.DISPATCHING);
            assertThat(notification.claimedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(notification.claimedBy()).isEqualTo(INSTANCE);
            assertThat(notification.updatedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("does not consume an attempt")
        void doesNotConsumeAnAttempt() {
            Notification notification = submitted();

            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThat(notification.attempts()).isZero();
        }

        @Test
        @DisplayName("cannot claim a notification that is already claimed")
        void cannotClaimTwice() {
            Notification notification = submitted();
            notification.claim(T0.plusSeconds(1), INSTANCE);

            assertThatThrownBy(
                            () -> notification.claim(T0.plusSeconds(2), "notification-service-7"))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("cannot claim a notification in a terminal state")
        void cannotClaimTerminal() {
            Notification sent = submitted();
            sent.claim(T0, INSTANCE);
            sent.recordAttempt(
                    new DispatchOutcome.Success(200),
                    T0,
                    T0.plusMillis(40),
                    Duration.ofMillis(40),
                    POLICY);

            assertThatThrownBy(() -> sent.claim(T0.plusSeconds(1), INSTANCE))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("requires a non-blank owner, because the reaper depends on it")
        void requiresOwner() {
            Notification notification = submitted();

            assertThatNullPointerException().isThrownBy(() -> notification.claim(T0, null));
            assertThatIllegalArgumentException().isThrownBy(() -> notification.claim(T0, "  "));
        }

        @Test
        @DisplayName("accepts an owner at the column width and rejects one over")
        void claimedByBoundary() {
            // The owner id comes from configuration. Rejected here it is one domain error;
            // accepted, it fails inside every claim UPDATE with a driver error and stops
            // dispatch altogether.
            Notification notification = submitted();
            notification.claim(T0, "x".repeat(128));

            assertThat(notification.claimedBy()).hasSize(128);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> submitted().claim(T0, "x".repeat(129)));
        }
    }

    @Nested
    @DisplayName("recording a successful attempt")
    class SuccessfulAttempt {

        @Test
        @DisplayName("moves to SENT and stamps the delivery time")
        void movesToSent() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.Success(200),
                    T0,
                    T0.plusMillis(39),
                    Duration.ofMillis(39),
                    POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.sentAt()).isEqualTo(T0.plusMillis(39));
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isNull();
        }

        @Test
        @DisplayName("releases the claim, so no row stays owned once it is done")
        void releasesTheClaim() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.Success(200),
                    T0,
                    T0.plusMillis(39),
                    Duration.ofMillis(39),
                    POLICY);

            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("produces an attempt record with everything the audit trail needs")
        void producesAttemptRecord() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            NotificationAttempt attempt =
                    notification.recordAttempt(
                            new DispatchOutcome.Success(200),
                            T0,
                            T0.plusMillis(39),
                            Duration.ofMillis(36),
                            POLICY);

            assertThat(attempt.id()).isNotNull();
            assertThat(attempt.notificationId()).isEqualTo(ID);
            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.channel()).isEqualTo(Channel.SERVICE);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(attempt.responseCode()).isEqualTo(200);
            assertThat(attempt.errorMessage()).isNull();
            assertThat(attempt.startedAt()).isEqualTo(T0);
            assertThat(attempt.finishedAt()).isEqualTo(T0.plusMillis(39));
            assertThat(attempt.durationMs())
                    .as("the measured duration, not the gap between the two instants")
                    .isEqualTo(36L);
        }
    }

    @Nested
    @DisplayName("recording a permanent failure")
    class PermanentFailureAttempt {

        @Test
        @DisplayName("fails immediately without spending the remaining attempts")
        void failsWithoutRetrying() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.PermanentFailure(400, "Bad Request"),
                    T0,
                    T0.plusMillis(12),
                    Duration.ofMillis(12),
                    POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isEqualTo("Bad Request");
            assertThat(notification.sentAt()).isNull();
        }

        @Test
        @DisplayName("releases the claim")
        void releasesTheClaim() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.PermanentFailure(404, "Not Found"),
                    T0,
                    T0.plusMillis(12),
                    Duration.ofMillis(12),
                    POLICY);

            assertThat(notification.claimedAt()).isNull();
            assertThat(notification.claimedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("recording a retryable failure")
    class RetryableFailureAttempt {

        @Test
        @DisplayName(
                "returns to PENDING scheduled by the backoff, counted from when the attempt ended")
        void reschedulesWithBackoff() {
            Notification notification = submitted();

            failRetryably(notification, T0);

            assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.lastError()).isEqualTo("Service Unavailable");
            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(5));
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("grows the delay with each failure")
        void growsTheDelay() {
            Notification notification = submitted();

            failRetryably(notification, T0);
            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(5));

            Instant second = T0.plusSeconds(6);
            failRetryably(notification, second);
            assertThat(notification.nextAttemptAt())
                    .isEqualTo(second.plusMillis(50).plusSeconds(15));
        }

        @Test
        @DisplayName("honours a Retry-After hint instead of the calculated backoff")
        void honoursRetryAfter() {
            // Ignoring the destination's Retry-After in favour of our own number is how a 429
            // turns into a second 429.
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            notification.recordAttempt(
                    new DispatchOutcome.RetryableFailure(
                            429, "Too Many Requests", Duration.ofSeconds(30)),
                    T0,
                    T0.plusMillis(50),
                    Duration.ofMillis(50),
                    POLICY);

            assertThat(notification.nextAttemptAt()).isEqualTo(T0.plusMillis(50).plusSeconds(30));
        }

        @Test
        @DisplayName("numbers the attempts consecutively from one")
        void numbersAttemptsConsecutively() {
            Notification notification = submitted();

            assertThat(failRetryably(notification, T0).attemptNumber()).isEqualTo(1);
            assertThat(failRetryably(notification, T0.plusSeconds(6)).attemptNumber()).isEqualTo(2);
            assertThat(failRetryably(notification, T0.plusSeconds(22)).attemptNumber())
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the attempt budget boundary")
    class AttemptBudgetBoundary {

        @Test
        @DisplayName("succeeding on the last allowed attempt ends in SENT, not FAILED")
        void successOnTheLastAttemptIsStillSuccess() {
            // The exact edge where a `>` written instead of a `>=` marks a delivered
            // notification as failed.
            Notification notification = submitted(3);

            failRetryably(notification, T0);
            failRetryably(notification, T0.plusSeconds(6));

            Instant third = T0.plusSeconds(22);
            notification.claim(third, INSTANCE);
            notification.recordAttempt(
                    new DispatchOutcome.Success(200),
                    third,
                    third.plusMillis(39),
                    Duration.ofMillis(39),
                    POLICY);

            assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.attempts()).isEqualTo(3);
            assertThat(notification.sentAt()).isEqualTo(third.plusMillis(39));
        }

        @Test
        @DisplayName("exhausting the budget ends in FAILED with the last error kept")
        void exhaustingTheBudgetFails() {
            Notification notification = submitted(3);

            failRetryably(notification, T0);
            failRetryably(notification, T0.plusSeconds(6));
            failRetryably(notification, T0.plusSeconds(22));

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(3);
            assertThat(notification.lastError()).isEqualTo("Service Unavailable");
            assertThat(notification.claimedBy()).isNull();
        }

        @Test
        @DisplayName("a budget of one leaves no room for a retry")
        void singleAttemptBudget() {
            Notification notification = submitted(1);

            failRetryably(notification, T0);

            assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("the final failing attempt is still recorded in the history")
        void finalAttemptIsStillRecorded() {
            // "Why did this end up FAILED?" has to be answerable from the attempt table
            // alone, which means the attempt that exhausted the budget cannot be dropped.
            Notification notification = submitted(1);

            NotificationAttempt attempt = failRetryably(notification, T0);

            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.errorMessage()).isEqualTo("Service Unavailable");
        }
    }

    @Nested
    @DisplayName("guarding the transition")
    class TransitionGuards {

        @Test
        @DisplayName("cannot record an attempt on a notification that was never claimed")
        void cannotRecordWithoutClaiming() {
            Notification notification = submitted();

            assertThatThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(39),
                                            POLICY))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("cannot record an attempt on a notification already in a terminal state")
        void cannotRecordOnTerminal() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);
            notification.recordAttempt(
                    new DispatchOutcome.Success(200),
                    T0,
                    T0.plusMillis(39),
                    Duration.ofMillis(39),
                    POLICY);

            assertThatThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(39),
                                            POLICY))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("rejects an attempt that finished before it started")
        void rejectsInvertedTimestamps() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0.plusMillis(39),
                                            T0,
                                            Duration.ofMillis(39),
                                            POLICY));
        }

        @Test
        @DisplayName("rejects a negative duration before spending an attempt")
        void rejectsNegativeDuration() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(-1),
                                            POLICY));
            assertThat(notification.attempts()).isZero();
            assertThat(notification.status()).isEqualTo(NotificationStatus.DISPATCHING);
        }

        @Test
        @DisplayName("requires an outcome, timestamps, a duration and a retry policy")
        void requiresMandatoryArguments() {
            Notification notification = submitted();
            notification.claim(T0, INSTANCE);

            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            null,
                                            T0,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(39),
                                            POLICY));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            null,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(39),
                                            POLICY));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            null,
                                            Duration.ofMillis(39),
                                            POLICY));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            T0.plusMillis(39),
                                            null,
                                            POLICY));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    notification.recordAttempt(
                                            new DispatchOutcome.Success(200),
                                            T0,
                                            T0.plusMillis(39),
                                            Duration.ofMillis(39),
                                            null));
        }
    }

    @Nested
    @DisplayName("rehydration")
    class Rehydration {

        private static final Instant UPDATED_AT = T0.plusSeconds(60);
        private static final Instant NEXT_ATTEMPT_AT = T0.plusSeconds(300);

        /** Varies only the lifecycle state, so what each guard rejects is unambiguous. */
        private Notification aRowIn(
                NotificationStatus status,
                int attempts,
                int maxAttempts,
                Instant claimedAt,
                String claimedBy) {
            return Notification.rehydrate(
                    ID,
                    payload(),
                    status,
                    attempts,
                    maxAttempts,
                    NEXT_ATTEMPT_AT,
                    "Service Unavailable",
                    claimedAt,
                    claimedBy,
                    CORRELATION_ID,
                    "idem-key-1",
                    T0,
                    UPDATED_AT,
                    null);
        }

        /** Varies only the arguments that have a NOT NULL column behind them. */
        private Notification aRowWith(
                UUID id,
                NotificationPayload payload,
                NotificationStatus status,
                Instant nextAttemptAt,
                CorrelationId correlationId,
                Instant createdAt,
                Instant updatedAt) {
            return Notification.rehydrate(
                    id,
                    payload,
                    status,
                    1,
                    MAX_ATTEMPTS,
                    nextAttemptAt,
                    null,
                    null,
                    null,
                    correlationId,
                    "idem-key-1",
                    createdAt,
                    updatedAt,
                    null);
        }

        @Test
        @DisplayName("requires an attempt budget of at least one")
        void requiresABudget() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aRowIn(NotificationStatus.PENDING, 0, 0, null, null));
        }

        @Test
        @DisplayName("rejects an attempt count outside the budget")
        void rejectsAttemptsOutsideTheBudget() {
            // Both ends: a row past its budget is as impossible as one with a negative count.
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> aRowIn(NotificationStatus.PENDING, -1, MAX_ATTEMPTS, null, null));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    aRowIn(
                                            NotificationStatus.PENDING,
                                            MAX_ATTEMPTS + 1,
                                            MAX_ATTEMPTS,
                                            null,
                                            null));
        }

        @Test
        @DisplayName("rejects a DISPATCHING notification that does not name its owner")
        void rejectsAClaimWithoutAnOwner() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    aRowIn(
                                            NotificationStatus.DISPATCHING,
                                            1,
                                            MAX_ATTEMPTS,
                                            null,
                                            INSTANCE));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    aRowIn(
                                            NotificationStatus.DISPATCHING,
                                            1,
                                            MAX_ATTEMPTS,
                                            T0.plusSeconds(30),
                                            null));
        }

        @Test
        @DisplayName("requires the identity, payload, status, schedule, trace and timestamps")
        void requiresMandatoryArguments() {
            // Every one of these has a NOT NULL column behind it, so a null here comes from the
            // mapper, not the data. That is exactly what this catches.
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            null,
                                            payload(),
                                            NotificationStatus.PENDING,
                                            NEXT_ATTEMPT_AT,
                                            CORRELATION_ID,
                                            T0,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            null,
                                            NotificationStatus.PENDING,
                                            NEXT_ATTEMPT_AT,
                                            CORRELATION_ID,
                                            T0,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            payload(),
                                            null,
                                            NEXT_ATTEMPT_AT,
                                            CORRELATION_ID,
                                            T0,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            payload(),
                                            NotificationStatus.PENDING,
                                            null,
                                            CORRELATION_ID,
                                            T0,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            payload(),
                                            NotificationStatus.PENDING,
                                            NEXT_ATTEMPT_AT,
                                            null,
                                            T0,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            payload(),
                                            NotificationStatus.PENDING,
                                            NEXT_ATTEMPT_AT,
                                            CORRELATION_ID,
                                            null,
                                            UPDATED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    aRowWith(
                                            ID,
                                            payload(),
                                            NotificationStatus.PENDING,
                                            NEXT_ATTEMPT_AT,
                                            CORRELATION_ID,
                                            T0,
                                            null));
        }
    }
}
