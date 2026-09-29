package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.domain.Notification;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts each accepted notification by channel and priority. */
@Component
final class MetricsIntakeEvents implements IntakeEvents {

    private final MeterRegistry registry;

    MetricsIntakeEvents(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void accepted(Notification notification) {
        registry.counter(
                        "notifications.accepted",
                        "channel",
                        notification.payload().channel().name(),
                        "priority",
                        notification.payload().priority().name())
                .increment();
    }
}
