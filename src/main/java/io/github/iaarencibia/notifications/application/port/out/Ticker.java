package io.github.iaarencibia.notifications.application.port.out;

/** A monotonic nanosecond counter, meaningful only as the difference between two readings. */
@FunctionalInterface
public interface Ticker {

    long nanos();
}
