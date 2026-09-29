package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** What each dispatch outcome becomes in the log. */
class LoggingDispatchEventsTest {

    private final LoggingDispatchEvents events = new LoggingDispatchEvents();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureTheLogger() {
        logger = (Logger) LoggerFactory.getLogger(LoggingDispatchEvents.class);
        logger.setLevel(Level.DEBUG);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void releaseTheLogger() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("logs a successful dispatch at DEBUG, with the notification, channel and outcome")
    void logsADispatchAtDebug() {
        Notification notification = notification();
        NotificationAttempt attempt = attempt(notification);

        events.dispatched(notification, attempt);

        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).contains(notification.id().toString());
        assertThat(fieldsOf(event)).containsEntry("channel", Channel.SERVICE);
        assertThat(fieldsOf(event)).containsEntry("outcome", AttemptOutcome.SUCCESS);
    }

    @Test
    @DisplayName("logs a lost claim at INFO, with the notification and the cause's message")
    void logsAClaimLostAtInfo() {
        Notification notification = notification();
        ClaimLostException cause = new ClaimLostException(notification.id());

        events.claimLost(notification, cause);

        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains(notification.id().toString());
        assertThat(event.getFormattedMessage()).contains(cause.getMessage());
    }

    @Test
    @DisplayName("logs an unexpected failure at ERROR, with the notification and the throwable")
    void logsAFailureAtError() {
        Notification notification = notification();
        RuntimeException cause = new IllegalStateException("boom");

        events.failed(notification, cause);

        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains(notification.id().toString());
        assertThat(event.getFormattedMessage())
                .as("an operator needs to know the row stays claimed")
                .contains("reaper");
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static NotificationAttempt attempt(Notification notification) {
        Instant now = Instant.now();
        return new NotificationAttempt(
                UUID.randomUUID(),
                notification.id(),
                1,
                Channel.SERVICE,
                AttemptOutcome.SUCCESS,
                200,
                null,
                now,
                now);
    }

    private static Notification notification() {
        return Notification.submit(
                UUID.randomUUID(),
                new NotificationPayload(
                        "someone@example.test",
                        Channel.SERVICE,
                        "Your order shipped",
                        "It is on its way.",
                        Priority.HIGH,
                        Map.of("orderId", "4471")),
                new CorrelationId("order-4471"),
                "key-1",
                3,
                Instant.now());
    }
}
