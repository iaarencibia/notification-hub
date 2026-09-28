package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers a notification by writing it as a structured log line: always present, the only channel
 * that cannot fail for a reason outside this process, so it reports {@link DispatchOutcome.Success}
 * with no status code -- there is none to report.
 *
 * <p>Fields are attached as key-value pairs, not concatenated into the message, so a log collector
 * can filter on them rather than search by substring. The body is left out: it is the widest field
 * a notification carries, and this line only has to say that delivery happened, to whom, and as
 * part of which operation.
 */
@Component
class LogChannelSender implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(LogChannelSender.class);

    @Override
    public Channel channel() {
        return Channel.LOG;
    }

    @Override
    public DispatchOutcome send(Notification notification) {
        // SLF4J's fluent API: the key-value pairs are read by the ECS formatter Spring Boot
        // already configures, so this adds no dependency and no encoder of its own.
        log.atInfo()
                .addKeyValue("notificationId", notification.id())
                .addKeyValue("correlationId", notification.correlationId().value())
                .addKeyValue("recipient", notification.payload().recipient())
                .addKeyValue("subject", notification.payload().subject())
                .addKeyValue("priority", notification.payload().priority())
                .addKeyValue("attempt", notification.attempts() + 1)
                .setMessage("Notification delivered to the log channel")
                .log();

        return new DispatchOutcome.Success(null);
    }
}
