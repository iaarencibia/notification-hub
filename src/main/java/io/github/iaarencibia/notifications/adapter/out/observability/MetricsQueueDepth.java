package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.QueueDepth;
import io.github.iaarencibia.notifications.application.port.out.QueueDepthEvents;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

/** Serves the last queue depth read as gauges, and NaN while there is none. */
@Component
final class MetricsQueueDepth implements QueueDepthEvents {

    private volatile QueueDepth latest;

    MetricsQueueDepth(MeterRegistry registry) {
        register(registry, NotificationStatus.PENDING, QueueDepth::pending);
        register(registry, NotificationStatus.DISPATCHING, QueueDepth::dispatching);
    }

    @Override
    public void measured(QueueDepth depth) {
        this.latest = depth;
    }

    @Override
    public void measurementFailed() {
        this.latest = null;
    }

    private void register(
            MeterRegistry registry, NotificationStatus status, ToLongFunction<QueueDepth> rows) {
        Gauge.builder("notifications.queue.rows", this, self -> self.read(rows))
                .tag("status", status.name())
                .register(registry);
    }

    private double read(ToLongFunction<QueueDepth> rows) {
        QueueDepth depth = latest;
        return depth == null ? Double.NaN : rows.applyAsLong(depth);
    }
}
