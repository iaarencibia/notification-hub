package io.github.iaarencibia.notifications.application.port.in.query;

import java.util.UUID;

/** Thrown when no notification has the requested id. */
public class NotificationNotFoundException extends RuntimeException {

    private final UUID id;

    public NotificationNotFoundException(UUID id) {
        super("no notification has the id " + id);
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
