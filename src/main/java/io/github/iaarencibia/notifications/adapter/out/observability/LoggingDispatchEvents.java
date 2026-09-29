package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Reports each dispatch outcome as a log line, at the level an operator would expect it. The ids
 * come from the notification, not the MDC: the dispatcher runs on pool threads no request filter
 * has touched.
 */
@Component
final class LoggingDispatchEvents implements DispatchEvents {

    private static final Logger log = LoggerFactory.getLogger(LoggingDispatchEvents.class);

    @Override
    public void claimed(Notification notification) {
        log.atDebug()
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("correlationId", notification.correlationId().value())
                .addKeyValue("claimedBy", notification.claimedBy())
                .setMessage("notification claimed")
                .log();
    }

    @Override
    public void dispatched(Notification notification, NotificationAttempt attempt) {
        LoggingEventBuilder line =
                log.atDebug()
                        .addKeyValue("notificationId", notification.id())
                        .addKeyValue("correlationId", notification.correlationId().value())
                        .addKeyValue("channel", notification.payload().channel())
                        .addKeyValue("attempt", attempt.attemptNumber())
                        .addKeyValue("outcome", attempt.outcome())
                        .addKeyValue("responseCode", attempt.responseCode())
                        .addKeyValue("durationMs", attempt.durationMs())
                        .addKeyValue("status", notification.status());
        if (notification.status() == NotificationStatus.PENDING) {
            line = line.addKeyValue("nextAttemptAt", notification.nextAttemptAt());
        }
        line.setMessage("dispatched notification " + notification.id()).log();
    }

    @Override
    public void claimLost(Notification notification, ClaimLostException cause) {
        log.atInfo()
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("correlationId", notification.correlationId().value())
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
                .addKeyValue("correlationId", notification.correlationId().value())
                .setCause(cause)
                .setMessage(
                        "failed to dispatch notification "
                                + notification.id()
                                + "; it stays claimed until the reaper releases it")
                .log();
    }
}
