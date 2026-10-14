package io.github.iaarencibia.notifications.application.port.out;

public record QueueDepth(long pending, long dispatching) {

    public QueueDepth {
        if (pending < 0 || dispatching < 0) {
            throw new IllegalArgumentException("a count must not be negative");
        }
    }
}
