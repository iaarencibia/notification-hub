package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Counts and times each dispatch outcome. */
@Component
final class MetricsDispatchEvents implements DispatchEvents {

    private static final String SENT = "sent";
    private static final String FAILED = "failed";
    private static final String CLAIM_LOST = "claim_lost";
    private static final String UNEXPECTED = "unexpected";

    private final MeterRegistry registry;

    MetricsDispatchEvents(MeterRegistry registry, DeliverableChannels deliverableChannels) {
        this.registry = registry;
        for (Channel channel : deliverableChannels.value()) {
            String name = channel.name();
            claims(name);
            retries(name);
            for (AttemptOutcome outcome : AttemptOutcome.values()) {
                attempts(name, outcome.name());
                duration(name, outcome.name());
            }
            for (String result : List.of(SENT, FAILED)) {
                settled(name, result);
            }
        }
        for (String kind : List.of(CLAIM_LOST, UNEXPECTED)) {
            errors(kind);
        }
    }

    @Override
    public void claimed(Notification notification) {
        claims(notification.payload().channel().name()).increment();
    }

    @Override
    public void dispatched(Notification notification, NotificationAttempt attempt) {
        String channel = notification.payload().channel().name();
        String outcome = attempt.outcome().name();

        attempts(channel, outcome).increment();
        duration(channel, outcome).record(Duration.ofMillis(attempt.durationMs()));

        switch (notification.status()) {
            case SENT -> settled(channel, SENT).increment();
            case FAILED -> settled(channel, FAILED).increment();
            case PENDING -> retries(channel).increment();
            default -> {}
        }
    }

    @Override
    public void claimLost(Notification notification, ClaimLostException cause) {
        errors(CLAIM_LOST).increment();
    }

    @Override
    public void failed(Notification notification, RuntimeException cause) {
        errors(UNEXPECTED).increment();
    }

    private Counter claims(String channel) {
        return registry.counter("notifications.claims", "channel", channel);
    }

    private Counter attempts(String channel, String outcome) {
        return registry.counter("notifications.attempts", "channel", channel, "outcome", outcome);
    }

    private Timer duration(String channel, String outcome) {
        return registry.timer(
                "notifications.delivery.duration", "channel", channel, "outcome", outcome);
    }

    private Counter retries(String channel) {
        return registry.counter("notifications.retries", "channel", channel);
    }

    private Counter settled(String channel, String result) {
        return registry.counter("notifications.settled", "channel", channel, "result", result);
    }

    private Counter errors(String kind) {
        return registry.counter("notifications.dispatch.errors", "kind", kind);
    }
}
