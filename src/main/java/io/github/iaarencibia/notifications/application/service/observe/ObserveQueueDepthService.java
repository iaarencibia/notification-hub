package io.github.iaarencibia.notifications.application.service.observe;

import io.github.iaarencibia.notifications.application.port.in.observe.ObserveQueueDepthUseCase;
import io.github.iaarencibia.notifications.application.port.out.QueueDepth;
import io.github.iaarencibia.notifications.application.port.out.QueueDepthEvents;
import io.github.iaarencibia.notifications.application.port.out.QueueInspection;
import java.util.Objects;

/** Reads the queue depth and reports it, or reports that it could not be read. */
public final class ObserveQueueDepthService implements ObserveQueueDepthUseCase {

    private final QueueInspection queue;
    private final QueueDepthEvents events;

    public ObserveQueueDepthService(QueueInspection queue, QueueDepthEvents events) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    @Override
    public void observeQueueDepth() {
        QueueDepth depth;
        try {
            depth = queue.measure();
        } catch (RuntimeException failure) {
            events.measurementFailed();
            throw failure;
        }
        events.measured(depth);
    }
}
