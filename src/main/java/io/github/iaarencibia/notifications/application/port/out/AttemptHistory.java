package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import java.util.List;
import java.util.UUID;

/** The attempts made to deliver a notification, ordered by attempt number. */
public interface AttemptHistory {

    List<NotificationAttempt> attemptsOf(UUID notificationId);
}
