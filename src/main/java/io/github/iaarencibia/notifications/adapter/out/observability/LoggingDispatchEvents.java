package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reports each dispatch outcome as a log line, at the level an operator would expect it. */
@Component
final class LoggingDispatchEvents implements DispatchEvents {

    private static final Logger log = LoggerFactory.getLogger(LoggingDispatchEvents.class);

    @Override
    public void dispatched(Notification notification, NotificationAttempt attempt) {
        log.atDebug()
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("channel", notification.payload().channel())
                .addKeyValue("outcome", attempt.outcome())
                .setMessage("dispatched notification " + notification.id())
                .log();
    }

    @Override
    public void claimLost(Notification notification, ClaimLostException cause) {
        log.atInfo()
                .addKeyValue("notificationId", notification.id())
                .setMessage(
                        "discarded the result of notification "
                                + notification.id()
                                + ": "
                                + cause.getMessage())
                .log();
    }

    @Override
    public void failed(Notification notification, RuntimeException cause) {
        log.atError()
                .addKeyValue("notificationId", notification.id())
                .setCause(cause)
                .setMessage(
                        "failed to dispatch notification "
                                + notification.id()
                                + "; it stays claimed until the reaper releases it")
                .log();
    }
}
