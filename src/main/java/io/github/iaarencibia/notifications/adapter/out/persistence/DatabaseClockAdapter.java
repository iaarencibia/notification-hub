package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The clock, read from PostgreSQL: the whole point of the port is that time comes from the database
 * every instance shares, not from the machine each one happens to run on.
 */
@Component
class DatabaseClockAdapter implements SystemClock {

    private final NotificationJpaStore store;

    DatabaseClockAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public Instant now() {
        return store.databaseNow();
    }
}
