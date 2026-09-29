package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That every listener hears every event, in the order they were given. */
class CompositeDispatchEventsTest {

    private final List<String> calls = new ArrayList<>();
    private final CompositeDispatchEvents events =
            new CompositeDispatchEvents(
                    List.of(
                            new RecordingEvents("first", calls),
                            new RecordingEvents("second", calls)));

    @Test
    @DisplayName("forwards a claim to every listener, in order")
    void forwardsClaimed() {
        events.claimed(notification());

        assertThat(calls).containsExactly("first:claimed", "second:claimed");
    }

    @Test
    @DisplayName("forwards a dispatch to every listener, in order")
    void forwardsDispatched() {
        events.dispatched(notification(), null);

        assertThat(calls).containsExactly("first:dispatched", "second:dispatched");
    }

    @Test
    @DisplayName("forwards a lost claim to every listener, in order")
    void forwardsClaimLost() {
        events.claimLost(notification(), new ClaimLostException(UUID.randomUUID()));

        assertThat(calls).containsExactly("first:claimLost", "second:claimLost");
    }

    @Test
    @DisplayName("forwards an unexpected failure to every listener, in order")
    void forwardsFailed() {
        events.failed(notification(), new IllegalStateException("boom"));

        assertThat(calls).containsExactly("first:failed", "second:failed");
    }

    private static Notification notification() {
        return Notification.submit(
                UUID.randomUUID(),
                new NotificationPayload(
                        "someone@example.test",
                        Channel.SERVICE,
                        "Your order shipped",
                        "It is on its way.",
                        Priority.HIGH,
                        Map.of()),
                new CorrelationId("order-4471"),
                "key-1",
                3,
                Instant.now());
    }

    private record RecordingEvents(String name, List<String> calls) implements DispatchEvents {

        @Override
        public void claimed(Notification notification) {
            calls.add(name + ":claimed");
        }

        @Override
        public void dispatched(Notification notification, NotificationAttempt attempt) {
            calls.add(name + ":dispatched");
        }

        @Override
        public void claimLost(Notification notification, ClaimLostException cause) {
            calls.add(name + ":claimLost");
        }

        @Override
        public void failed(Notification notification, RuntimeException cause) {
            calls.add(name + ":failed");
        }
    }
}
