package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Counts and times each dispatch outcome. */
@Component
final class MetricsDispatchEvents implements DispatchEvents {

    private final MeterRegistry registry;

    MetricsDispatchEvents(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void claimed(Notification notification) {}

    @Override
    public void dispatched(Notification notification, NotificationAttempt attempt) {
        String channel = notification.payload().channel().name();
        String outcome = attempt.outcome().name();

        registry.counter("notifications.attempts", "channel", channel, "outcome", outcome)
                .increment();
        registry.timer("notifications.delivery.duration", "channel", channel, "outcome", outcome)
                .record(Duration.ofMillis(attempt.durationMs()));

        switch (notification.status()) {
            case SENT -> settled(channel, "sent");
            case FAILED -> settled(channel, "failed");
            case PENDING ->
                    registry.counter("notifications.retries", "channel", channel).increment();
            default -> {}
        }
    }

    @Override
    public void claimLost(Notification notification, ClaimLostException cause) {
        error("claim_lost");
    }

    @Override
    public void failed(Notification notification, RuntimeException cause) {
        error("unexpected");
    }

    private void settled(String channel, String result) {
        registry.counter("notifications.settled", "channel", channel, "result", result).increment();
    }

    private void error(String kind) {
        registry.counter("notifications.dispatch.errors", "kind", kind).increment();
    }
}
