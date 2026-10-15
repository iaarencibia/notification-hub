package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What each dispatch outcome becomes in the metrics. */
class MetricsDispatchEventsTest {

    private static final Set<Channel> DELIVERABLE = Set.of(Channel.LOG, Channel.SERVICE);
    private static final Instant ACCEPTED = Instant.parse("2026-10-14T09:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsDispatchEvents events =
            new MetricsDispatchEvents(registry, new DeliverableChannels(DELIVERABLE));

    @Test
    @DisplayName("exports every per-channel series at zero before any dispatch")
    void startsEveryChannelSeriesAtZero() {
        for (Channel channel : DELIVERABLE) {
            String name = channel.name();
            assertThat(count("notifications.claims", "channel", name)).isZero();
            assertThat(count("notifications.retries", "channel", name)).isZero();
            for (AttemptOutcome outcome : AttemptOutcome.values()) {
                assertThat(
                                count(
                                        "notifications.attempts",
                                        "channel",
                                        name,
                                        "outcome",
                                        outcome.name()))
                        .isZero();
                assertThat(duration(name, outcome.name()).count()).isZero();
            }
            for (String result : List.of("sent", "failed")) {
                assertThat(count("notifications.settled", "channel", name, "result", result))
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("exports every dispatch error kind at zero before any dispatch")
    void startsEveryErrorKindAtZero() {
        for (String kind : List.of("claim_lost", "unexpected")) {
            assertThat(count("notifications.dispatch.errors", "kind", kind)).isZero();
        }
    }

    @Test
    @DisplayName("exports nothing for a channel this deployment cannot deliver")
    void leavesUndeliverableChannelsOut() {
        assertThat(registry.find("notifications.claims").tag("channel", "EMAIL").meters())
                .isEmpty();
        assertThat(
                        registry.find("notifications.delivery.duration")
                                .tag("channel", "EMAIL")
                                .meters())
                .isEmpty();
    }

    @Test
    @DisplayName("counts an attempt and times it, under its channel and outcome")
    void countsAndTimesTheAttempt() {
        Notification notification = notification(NotificationStatus.SENT);
        assertThat(count("notifications.attempts", "channel", "SERVICE", "outcome", "SUCCESS"))
                .isZero();
        assertThat(duration("SERVICE", "SUCCESS").count()).isZero();

        events.dispatched(notification, attempt(notification, AttemptOutcome.SUCCESS));

        assertThat(count("notifications.attempts", "channel", "SERVICE", "outcome", "SUCCESS"))
                .isEqualTo(1);
        assertThat(duration("SERVICE", "SUCCESS").count()).isEqualTo(1);
        assertThat(duration("SERVICE", "SUCCESS").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500);
    }

    @Test
    @DisplayName("counts a notification that ends SENT as settled, and not as a retry")
    void countsASentNotificationAsSettled() {
        Notification notification = notification(NotificationStatus.SENT);
        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "sent")).isZero();

        events.dispatched(notification, attempt(notification, AttemptOutcome.SUCCESS));

        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "sent"))
                .isEqualTo(1);
        assertThat(count("notifications.retries", "channel", "SERVICE")).isZero();
    }

    @Test
    @DisplayName("counts a notification that ends FAILED as settled, and not as a retry")
    void countsAFailedNotificationAsSettled() {
        Notification notification = notification(NotificationStatus.FAILED);
        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "failed"))
                .isZero();

        events.dispatched(notification, attempt(notification, AttemptOutcome.PERMANENT_FAILURE));

        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "failed"))
                .isEqualTo(1);
        assertThat(count("notifications.retries", "channel", "SERVICE")).isZero();
    }

    @Test
    @DisplayName("counts a notification that goes back to PENDING as a retry, and not as settled")
    void countsAPendingNotificationAsARetry() {
        Notification notification = notification(NotificationStatus.PENDING);
        assertThat(count("notifications.retries", "channel", "SERVICE")).isZero();

        events.dispatched(notification, attempt(notification, AttemptOutcome.RETRYABLE_FAILURE));

        assertThat(count("notifications.retries", "channel", "SERVICE")).isEqualTo(1);
        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "sent")).isZero();
        assertThat(count("notifications.settled", "channel", "SERVICE", "result", "failed"))
                .isZero();
    }

    @Test
    @DisplayName("counts a lost claim as a dispatch error of kind claim_lost")
    void countsALostClaim() {
        assertThat(count("notifications.dispatch.errors", "kind", "claim_lost")).isZero();

        events.claimLost(
                notification(NotificationStatus.PENDING),
                new ClaimLostException(UUID.randomUUID()));

        assertThat(count("notifications.dispatch.errors", "kind", "claim_lost")).isEqualTo(1);
    }

    @Test
    @DisplayName("counts an unexpected failure as a dispatch error of kind unexpected")
    void countsAnUnexpectedFailure() {
        assertThat(count("notifications.dispatch.errors", "kind", "unexpected")).isZero();

        events.failed(notification(NotificationStatus.PENDING), new IllegalStateException("boom"));

        assertThat(count("notifications.dispatch.errors", "kind", "unexpected")).isEqualTo(1);
    }

    @Test
    @DisplayName("counts each claimed notification under its channel")
    void countsEachClaim() {
        assertThat(count("notifications.claims", "channel", "SERVICE")).isZero();

        events.claimed(notification(NotificationStatus.PENDING), Duration.ZERO);
        events.claimed(notification(NotificationStatus.PENDING), Duration.ZERO);

        assertThat(count("notifications.claims", "channel", "SERVICE")).isEqualTo(2);
    }

    @Test
    @DisplayName("exports the claim delay of every priority at zero before any claim")
    void startsEveryPriorityClaimDelayAtZero() {
        for (Priority priority : Priority.values()) {
            assertThat(claimDelay(priority).count()).isZero();
        }
    }

    @Test
    @DisplayName("times how long a claimed notification waited, under its priority only")
    void timesTheClaimDelayUnderItsPriority() {
        assertThat(claimDelay(Priority.HIGH).count()).isZero();

        events.claimed(notification(NotificationStatus.PENDING), Duration.ofMillis(2500));

        assertThat(claimDelay(Priority.HIGH).count()).isEqualTo(1);
        assertThat(claimDelay(Priority.HIGH).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2500);
        assertThat(claimDelay(Priority.MEDIUM).count()).isZero();
        assertThat(claimDelay(Priority.LOW).count()).isZero();
        assertThat(registry.get("notifications.claim.delay").timers())
                .as("no channel tag: it would multiply the histogram series for nothing")
                .allSatisfy(timer -> assertThat(timer.getId().getTag("channel")).isNull());
    }

    @Test
    @DisplayName(
            "exports the delivery latency of every priority and result at zero before any dispatch")
    void startsEveryDeliveryLatencyAtZero() {
        for (Priority priority : Priority.values()) {
            for (String result : List.of("sent", "failed")) {
                assertThat(latency(priority, result).count()).isZero();
            }
        }
    }

    @Test
    @DisplayName("times a notification settled SENT from its acceptance to the end of that attempt")
    void timesTheLatencyOfASentNotification() {
        Notification notification = notification(NotificationStatus.SENT);
        assertThat(latency(Priority.HIGH, "sent").count()).isZero();

        events.dispatched(
                notification,
                attempt(notification, AttemptOutcome.SUCCESS, ACCEPTED.plusMillis(64_500)));

        assertThat(latency(Priority.HIGH, "sent").count()).isEqualTo(1);
        assertThat(latency(Priority.HIGH, "sent").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(64_500);
        assertThat(latency(Priority.HIGH, "failed").count()).isZero();
        assertThat(registry.get("notifications.delivery.latency").timers())
                .as("no channel tag: it would multiply the histogram series for nothing")
                .allSatisfy(timer -> assertThat(timer.getId().getTag("channel")).isNull());
    }

    @Test
    @DisplayName(
            "times a notification settled FAILED from its acceptance to the end of that attempt")
    void timesTheLatencyOfAFailedNotification() {
        Notification notification = notification(NotificationStatus.FAILED);
        assertThat(latency(Priority.HIGH, "failed").count()).isZero();

        events.dispatched(
                notification,
                attempt(
                        notification,
                        AttemptOutcome.PERMANENT_FAILURE,
                        ACCEPTED.plusMillis(7_250)));

        assertThat(latency(Priority.HIGH, "failed").count()).isEqualTo(1);
        assertThat(latency(Priority.HIGH, "failed").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(7_250);
        assertThat(latency(Priority.HIGH, "sent").count()).isZero();
    }

    @Test
    @DisplayName("times no delivery latency for a notification that goes back to PENDING")
    void timesNoLatencyOnARetry() {
        Notification notification = notification(NotificationStatus.PENDING);

        events.dispatched(notification, attempt(notification, AttemptOutcome.RETRYABLE_FAILURE));

        assertThat(registry.get("notifications.delivery.latency").timers())
                .allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

    @Test
    @DisplayName("times a settlement that precedes its acceptance as zero, so it is still counted")
    void timesANegativeLatencyAsZero() {
        Notification notification = notification(NotificationStatus.SENT);

        events.dispatched(
                notification,
                attempt(notification, AttemptOutcome.SUCCESS, ACCEPTED.minusSeconds(2)));

        assertThat(latency(Priority.HIGH, "sent").count()).isEqualTo(1);
        assertThat(latency(Priority.HIGH, "sent").totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private Timer duration(String channel, String outcome) {
        return registry.get("notifications.delivery.duration")
                .tags("channel", channel, "outcome", outcome)
                .timer();
    }

    private Timer claimDelay(Priority priority) {
        return registry.get("notifications.claim.delay").tag("priority", priority.name()).timer();
    }

    private Timer latency(Priority priority, String result) {
        return registry.get("notifications.delivery.latency")
                .tags("priority", priority.name(), "result", result)
                .timer();
    }

    private static NotificationAttempt attempt(Notification notification, AttemptOutcome outcome) {
        return attempt(notification, outcome, ACCEPTED.plusSeconds(60));
    }

    private static NotificationAttempt attempt(
            Notification notification, AttemptOutcome outcome, Instant finishedAt) {
        return new NotificationAttempt(
                UUID.randomUUID(),
                notification.id(),
                1,
                Channel.SERVICE,
                outcome,
                200,
                outcome == AttemptOutcome.SUCCESS ? null : "boom",
                finishedAt.minusMillis(4500),
                finishedAt,
                1500L);
    }

    private static Notification notification(NotificationStatus status) {
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
                ACCEPTED.plusSeconds(30),
                status == NotificationStatus.SENT ? null : "boom",
                null,
                null,
                new CorrelationId("order-4471"),
                null,
                ACCEPTED,
                ACCEPTED,
                status == NotificationStatus.SENT ? ACCEPTED : null);
    }
}
