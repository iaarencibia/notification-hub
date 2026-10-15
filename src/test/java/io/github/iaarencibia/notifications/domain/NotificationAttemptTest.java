package io.github.iaarencibia.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The aggregate is the only caller today and pre-validates everything it passes. These invariants
 * exist for callers that come later, such as the persistence mapper rebuilding an attempt from a
 * row.
 */
class NotificationAttemptTest {

    private static final UUID ATTEMPT_ID = UUID.fromString("7a1e0000-0000-4000-8000-000000000001");
    private static final UUID NOTIFICATION_ID =
            UUID.fromString("3f2a1c40-0000-4000-8000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-08-30T10:00:00Z");

    private static NotificationAttempt valid() {
        return new NotificationAttempt(
                ATTEMPT_ID,
                NOTIFICATION_ID,
                1,
                Channel.SERVICE,
                AttemptOutcome.RETRYABLE_FAILURE,
                503,
                "Service Unavailable",
                STARTED_AT,
                STARTED_AT.plusMillis(48),
                48L);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("keeps everything the attempt history needs to answer for itself")
        void keepsEveryField() {
            NotificationAttempt attempt = valid();

            assertThat(attempt.id()).isEqualTo(ATTEMPT_ID);
            assertThat(attempt.notificationId()).isEqualTo(NOTIFICATION_ID);
            assertThat(attempt.attemptNumber()).isEqualTo(1);
            assertThat(attempt.channel()).isEqualTo(Channel.SERVICE);
            assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
            assertThat(attempt.responseCode()).isEqualTo(503);
            assertThat(attempt.errorMessage()).isEqualTo("Service Unavailable");
            assertThat(attempt.startedAt()).isEqualTo(STARTED_AT);
            assertThat(attempt.finishedAt()).isEqualTo(STARTED_AT.plusMillis(48));
            assertThat(attempt.durationMs()).isEqualTo(48L);
        }

        @Test
        @DisplayName("allows a null response code and a null error message")
        void allowsTheNullableColumns() {
            // A successful LOG delivery has neither: no status code to report, nothing went wrong.
            NotificationAttempt attempt =
                    new NotificationAttempt(
                            ATTEMPT_ID,
                            NOTIFICATION_ID,
                            1,
                            Channel.LOG,
                            AttemptOutcome.SUCCESS,
                            null,
                            null,
                            STARTED_AT,
                            STARTED_AT.plusMillis(2),
                            2L);

            assertThat(attempt.responseCode()).isNull();
            assertThat(attempt.errorMessage()).isNull();
        }

        @Test
        @DisplayName("requires a reason on a failed attempt, as the check constraint does")
        void failureRequiresAReason() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.RETRYABLE_FAILURE,
                                            503,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.PERMANENT_FAILURE,
                                            400,
                                            "   ",
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
        }

        @Test
        @DisplayName("requires the identifiers, the channel and the outcome")
        void requiresMandatoryFields() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            null,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            null,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            null,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            null,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
        }

        @Test
        @DisplayName("requires both timestamps")
        void requiresBothTimestamps() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            null,
                                            STARTED_AT,
                                            0L));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            null,
                                            0L));
        }

        @Test
        @DisplayName("numbers attempts from one, matching the ck_attempt_number constraint")
        void requiresPositiveAttemptNumber() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            0,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            -1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            0L));
        }

        @Test
        @DisplayName("rejects an attempt that finished before it started")
        void rejectsInvertedTimestamps() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT.plusMillis(1),
                                            STARTED_AT,
                                            0L));
        }
    }

    @Nested
    @DisplayName("duration")
    class Duration {

        @Test
        @DisplayName("is the measured value, even when the two instants are further apart")
        void isTheMeasuredValue() {
            NotificationAttempt attempt =
                    new NotificationAttempt(
                            ATTEMPT_ID,
                            NOTIFICATION_ID,
                            2,
                            Channel.SERVICE,
                            AttemptOutcome.RETRYABLE_FAILURE,
                            null,
                            "Read timed out",
                            STARTED_AT,
                            STARTED_AT.plusSeconds(8),
                            5_000L);

            assertThat(attempt.durationMs()).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("may be zero, for a delivery shorter than a millisecond")
        void allowsZero() {
            // The LOG channel writes to stdout: a delivery can plausibly take less than a
            // millisecond.
            NotificationAttempt attempt =
                    new NotificationAttempt(
                            ATTEMPT_ID,
                            NOTIFICATION_ID,
                            1,
                            Channel.LOG,
                            AttemptOutcome.SUCCESS,
                            null,
                            null,
                            STARTED_AT,
                            STARTED_AT,
                            0L);

            assertThat(attempt.durationMs()).isZero();
        }

        @Test
        @DisplayName("is never negative")
        void rejectsNegative() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new NotificationAttempt(
                                            ATTEMPT_ID,
                                            NOTIFICATION_ID,
                                            1,
                                            Channel.SERVICE,
                                            AttemptOutcome.SUCCESS,
                                            200,
                                            null,
                                            STARTED_AT,
                                            STARTED_AT,
                                            -1L));
        }
    }
}
