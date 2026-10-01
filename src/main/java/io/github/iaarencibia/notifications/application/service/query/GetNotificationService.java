package io.github.iaarencibia.notifications.application.service.query;

import io.github.iaarencibia.notifications.application.port.in.query.GetNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.in.query.NotificationDetails;
import io.github.iaarencibia.notifications.application.port.in.query.NotificationNotFoundException;
import io.github.iaarencibia.notifications.application.port.out.AttemptHistory;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import java.util.Objects;
import java.util.UUID;

/** Reads a notification together with its attempts. */
public final class GetNotificationService implements GetNotificationUseCase {

    private final NotificationRepository repository;
    private final AttemptHistory history;

    public GetNotificationService(NotificationRepository repository, AttemptHistory history) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
    }

    @Override
    public NotificationDetails get(UUID id) {
        Objects.requireNonNull(id, "id must not be null");

        return repository
                .findById(id)
                .map(notification -> new NotificationDetails(notification, history.attemptsOf(id)))
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }
}
