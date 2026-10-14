package io.github.iaarencibia.notifications.application.port.out;

/** Where a reading of the queue depth, or the loss of one, is reported. */
public interface QueueDepthEvents {

    void measured(QueueDepth depth);

    void measurementFailed();
}
