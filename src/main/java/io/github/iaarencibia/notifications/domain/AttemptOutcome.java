package io.github.iaarencibia.notifications.domain;

/**
 * The label stored in {@code notification_attempt.outcome}, matching the values the {@code
 * ck_attempt_outcome} check constraint accepts.
 */
public enum AttemptOutcome {
    SUCCESS,

    RETRYABLE_FAILURE,

    PERMANENT_FAILURE
}
