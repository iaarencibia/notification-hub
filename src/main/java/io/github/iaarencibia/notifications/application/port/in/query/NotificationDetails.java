package io.github.iaarencibia.notifications.application.port.in.query;

import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import java.util.List;

/** A notification and its attempts, oldest first. */
public record NotificationDetails(Notification notification, List<NotificationAttempt> attempts) {}
