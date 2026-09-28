package io.github.iaarencibia.notifications.application.port.out;

import java.time.Duration;

/**
 * Releasing work whose owner never came back. A port of its own so the reaper cannot claim
 * anything.
 */
public interface AbandonedClaims {

    /**
     * Measured against the database clock.
     *
     * @return how many claims were released
     */
    int release(Duration staleAfter);
}
