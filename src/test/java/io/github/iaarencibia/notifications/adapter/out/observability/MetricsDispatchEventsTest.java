package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What each dispatch outcome becomes in the metrics. */
class MetricsDispatchEventsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsDispatchEvents events = new MetricsDispatchEvents(registry);

    @Test
    @DisplayName("counts an attempt and times it, under its channel and outcome")
    void countsAndTimesTheAttempt() {
        Notification notification = notification(NotificationStatus.SENT);

        events.dispatched(notification, attempt(notification, AttemptOutcome.SUCCESS));

        assertThat(
                        registry.get("notifications.attempts")
                                .tags("channel", "SERVICE", "outcome", "SUCCESS")
                                .counter()
                                .count())
                .isEqualTo(1);
        var timer =
                registry.get("notifications.delivery.duration")
                        .tags("channel", "SERVICE", "outcome", "SUCCESS")
                        .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500);
    }

    @Test
    @DisplayName("counts a notification that ends SENT as settled, and not as a retry")
    void countsASentNotificationAsSettled() {
        Notification notification = notification(NotificationStatus.SENT);

        events.dispatched(notification, attempt(notification, AttemptOutcome.SUCCESS));

        assertThat(
                        registry.get("notifications.settled")
                                .tags("channel", "SERVICE", "result", "sent")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(registry.find("notifications.retries").counter()).isNull();
    }

    @Test
    @DisplayName("counts a notification that ends FAILED as settled, and not as a retry")
    void countsAFailedNotificationAsSettled() {
        Notification notification = notification(NotificationStatus.FAILED);

        events.dispatched(notification, attempt(notification, AttemptOutcome.PERMANENT_FAILURE));

        assertThat(
                        registry.get("notifications.settled")
                                .tags("channel", "SERVICE", "result", "failed")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(registry.find("notifications.retries").counter()).isNull();
    }

    @Test
    @DisplayName("counts a notification that goes back to PENDING as a retry, and not as settled")
    void countsAPendingNotificationAsARetry() {
        Notification notification = notification(NotificationStatus.PENDING);

        events.dispatched(notification, attempt(notification, AttemptOutcome.RETRYABLE_FAILURE));

        assertThat(
                        registry.get("notifications.retries")
                                .tag("channel", "SERVICE")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(registry.find("notifications.settled").counter()).isNull();
    }

    @Test
    @DisplayName("counts a lost claim as a dispatch error of kind claim_lost")
    void countsALostClaim() {
        events.claimLost(
                notification(NotificationStatus.PENDING),
                new ClaimLostException(UUID.randomUUID()));

        assertThat(
                        registry.get("notifications.dispatch.errors")
                                .tag("kind", "claim_lost")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("counts an unexpected failure as a dispatch error of kind unexpected")
    void countsAnUnexpectedFailure() {
        events.failed(notification(NotificationStatus.PENDING), new IllegalStateException("boom"));

        assertThat(
                        registry.get("notifications.dispatch.errors")
                                .tag("kind", "unexpected")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("counts each claimed notification under its channel")
    void countsEachClaim() {
        events.claimed(notification(NotificationStatus.PENDING));
        events.claimed(notification(NotificationStatus.PENDING));

        assertThat(registry.get("notifications.claims").tag("channel", "SERVICE").counter().count())
                .isEqualTo(2);
    }

    private static NotificationAttempt attempt(Notification notification, AttemptOutcome outcome) {
        Instant start = Instant.now();
        return new NotificationAttempt(
                UUID.randomUUID(),
                notification.id(),
                1,
                Channel.SERVICE,
                outcome,
                200,
                outcome == AttemptOutcome.SUCCESS ? null : "boom",
                start,
                start.plusMillis(1500));
    }

    private static Notification notification(NotificationStatus status) {
        Instant now = Instant.now();
        return Notification.rehydrate(
                UUID.randomUUID(),
                new NotificationPayload(
                        "someone@example.test",
                        Channel.SERVICE,
                        "Your order shipped",
                        "It is on its way.",
                        Priority.HIGH,
                        Map.of()),
                status,
                1,
                3,
                now.plusSeconds(30),
                status == NotificationStatus.SENT ? null : "boom",
                null,
                null,
                new CorrelationId("order-4471"),
                null,
                now,
                now,
                status == NotificationStatus.SENT ? now : null);
    }
}
