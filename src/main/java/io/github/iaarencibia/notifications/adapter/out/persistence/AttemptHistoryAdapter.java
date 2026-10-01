package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.AttemptHistory;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AttemptHistoryAdapter implements AttemptHistory {

    private final NotificationJpaStore store;

    AttemptHistoryAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public List<NotificationAttempt> attemptsOf(UUID notificationId) {
        return store.attemptsOf(notificationId).stream().map(NotificationMapper::toDomain).toList();
    }
}
