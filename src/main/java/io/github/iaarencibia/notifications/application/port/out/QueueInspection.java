package io.github.iaarencibia.notifications.application.port.out;

/** Reading how much work is queued. A port of its own so the observer can change nothing. */
public interface QueueInspection {

    /**
     * @return the rows currently PENDING and DISPATCHING, zero for a status with none
     */
    QueueDepth measure();
}
