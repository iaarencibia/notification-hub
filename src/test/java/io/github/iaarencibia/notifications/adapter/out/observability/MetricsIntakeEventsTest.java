package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What intake becomes in the metrics. */
class MetricsIntakeEventsTest {

    private static final Set<Channel> DELIVERABLE = Set.of(Channel.LOG, Channel.SERVICE);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsIntakeEvents events =
            new MetricsIntakeEvents(registry, new DeliverableChannels(DELIVERABLE));

    @Test
    @DisplayName("exports every deliverable channel and priority at zero before any intake")
    void startsEveryAcceptedSeriesAtZero() {
        for (Channel channel : DELIVERABLE) {
            for (Priority priority : Priority.values()) {
                assertThat(accepted(channel, priority)).isZero();
            }
        }
    }

    @Test
    @DisplayName("exports every deliverable channel's replays at zero before any intake")
    void startsEveryReplayedSeriesAtZero() {
        for (Channel channel : DELIVERABLE) {
            assertThat(replayed(channel)).isZero();
        }
    }

    @Test
    @DisplayName("exports nothing for a channel this deployment cannot deliver")
    void leavesUndeliverableChannelsOut() {
        assertThat(registry.find("notifications.accepted").tag("channel", "EMAIL").counters())
                .isEmpty();
        assertThat(registry.find("notifications.replayed").tag("channel", "EMAIL").counters())
                .isEmpty();
    }

    @Test
    @DisplayName("counts each replay under the channel of the notification it answered with")
    void countsReplaysPerChannel() {
        assertThat(replayed(Channel.SERVICE)).isZero();

        events.replayed(notification(Channel.SERVICE, Priority.HIGH));
        events.replayed(notification(Channel.SERVICE, Priority.LOW));

        assertThat(replayed(Channel.SERVICE)).isEqualTo(2);
        assertThat(replayed(Channel.LOG)).isZero();
    }

    @Test
    @DisplayName("counts each accepted notification under its own channel and priority")
    void countsAcceptedNotificationsPerChannelAndPriority() {
        assertThat(accepted(Channel.SERVICE, Priority.HIGH)).isZero();
        assertThat(accepted(Channel.LOG, Priority.LOW)).isZero();

        events.accepted(notification(Channel.SERVICE, Priority.HIGH));
        events.accepted(notification(Channel.LOG, Priority.LOW));

        assertThat(accepted(Channel.SERVICE, Priority.HIGH)).isEqualTo(1);
        assertThat(accepted(Channel.LOG, Priority.LOW)).isEqualTo(1);
    }

    private double accepted(Channel channel, Priority priority) {
        return registry.get("notifications.accepted")
                .tags("channel", channel.name(), "priority", priority.name())
                .counter()
                .count();
    }

    private double replayed(Channel channel) {
        return registry.get("notifications.replayed")
                .tag("channel", channel.name())
                .counter()
                .count();
    }

    private static Notification notification(Channel channel, Priority priority) {
        return Notification.submit(
                UUID.randomUUID(),
                new NotificationPayload(
                        "someone@example.test",
                        channel,
                        "Your order shipped",
                        "It is on its way.",
                        priority,
                        Map.of()),
                new CorrelationId("order-4471"),
                "key-1",
                3,
                Instant.now());
    }
}
