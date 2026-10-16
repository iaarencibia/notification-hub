package io.github.iaarencibia.notifications.application.port.out;

/** Reads how much work is queued. */
public interface QueueInspection {

    /**
     * @return the rows currently PENDING and DISPATCHING, zero for a status with none
     */
    QueueDepth measure();
}
