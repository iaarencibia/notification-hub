package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What intake becomes in the metrics. */
class MetricsIntakeEventsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsIntakeEvents events = new MetricsIntakeEvents(registry);

    @Test
    @DisplayName("counts each accepted notification under its own channel and priority")
    void countsAcceptedNotificationsPerChannelAndPriority() {
        events.accepted(notification(Channel.SERVICE, Priority.HIGH));
        events.accepted(notification(Channel.LOG, Priority.LOW));

        assertThat(
                        registry.get("notifications.accepted")
                                .tags("channel", "SERVICE", "priority", "HIGH")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("notifications.accepted")
                                .tags("channel", "LOG", "priority", "LOW")
                                .counter()
                                .count())
                .isEqualTo(1);
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
