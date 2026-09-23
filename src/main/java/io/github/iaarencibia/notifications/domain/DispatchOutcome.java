package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.util.Objects;

/** The result a channel reports after attempting one delivery. */
public sealed interface DispatchOutcome {

    /**
     * @return the value stored in {@code notification_attempt.outcome}
     */
    AttemptOutcome attemptOutcome();

    /**
     * @return the status code, or {@code null} when none arrived
     */
    Integer responseCode();

    /**
     * @return the reason, or {@code null} when the attempt succeeded
     */
    String errorMessage();

    record Success(Integer responseCode) implements DispatchOutcome {

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.SUCCESS;
        }

        @Override
        public String errorMessage() {
            return null;
        }
    }

    /**
     * Failed for a reason that may not repeat, so the notification is rescheduled while it still
     * has attempts left.
     *
     * @param retryAfter from the {@code Retry-After} header; {@code null} means none was given,
     *     distinct from {@code Duration.ZERO} which means retry immediately
     */
    record RetryableFailure(Integer responseCode, String errorMessage, Duration retryAfter)
            implements DispatchOutcome {

        public RetryableFailure {
            errorMessage = requireReason(errorMessage);
            if (retryAfter != null && retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.RETRYABLE_FAILURE;
        }
    }

    /** Rejected for a reason that retrying would not change. */
    record PermanentFailure(Integer responseCode, String errorMessage) implements DispatchOutcome {

        public PermanentFailure {
            errorMessage = requireReason(errorMessage);
        }

        @Override
        public AttemptOutcome attemptOutcome() {
            return AttemptOutcome.PERMANENT_FAILURE;
        }
    }

    /**
     * A failure with no recorded reason cannot answer why a notification ended up {@code FAILED},
     * which is the question the attempt history exists to answer.
     */
    private static String requireReason(String errorMessage) {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        if (errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return errorMessage;
    }
}
