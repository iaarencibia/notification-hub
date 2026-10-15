package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.Priority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts each accepted notification by channel and priority, and each replay by channel. */
@Component
final class MetricsIntakeEvents implements IntakeEvents {

    private final MeterRegistry registry;

    MetricsIntakeEvents(MeterRegistry registry, DeliverableChannels deliverableChannels) {
        this.registry = registry;
        for (Channel channel : deliverableChannels.value()) {
            for (Priority priority : Priority.values()) {
                accepted(channel, priority);
            }
            replayed(channel);
        }
    }

    @Override
    public void accepted(Notification notification) {
        accepted(notification.payload().channel(), notification.payload().priority()).increment();
    }

    @Override
    public void replayed(Notification original) {
        replayed(original.payload().channel()).increment();
    }

    private Counter accepted(Channel channel, Priority priority) {
        return registry.counter(
                "notifications.accepted", "channel", channel.name(), "priority", priority.name());
    }

    private Counter replayed(Channel channel) {
        return registry.counter("notifications.replayed", "channel", channel.name());
    }
}
