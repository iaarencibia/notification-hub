package io.github.iaarencibia.notifications.application.port.out;

import java.time.Instant;

/**
 * The clock, as an outbound dependency rather than a call to {@code Instant.now()}: it has to be
 * the database's, since every claim decision is compared against it.
 */
public interface SystemClock {

    Instant now();
}
