package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;

/** What happened to one delivery, reported in the language of the domain. */
public interface DispatchEvents {

    void claimed(Notification notification);

    void dispatched(Notification notification, NotificationAttempt attempt);

    void claimLost(Notification notification, ClaimLostException cause);

    void failed(Notification notification, RuntimeException cause);
}
