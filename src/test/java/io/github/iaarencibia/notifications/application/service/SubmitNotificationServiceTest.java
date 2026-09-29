package io.github.iaarencibia.notifications.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.UndeliverableChannelException;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The intake use case, with storage replaced by a hand-written double enforcing the one rule the
 * real adapter delegates to a unique index: save, collide, look up, answer.
 */
class SubmitNotificationServiceTest {

    private static final CorrelationId CORRELATION_ID = new CorrelationId("order-4471");
    private static final int MAX_ATTEMPTS = 3;

    /** What the deployment under test can deliver. The payload below uses SERVICE. */
    private static final DeliverableChannels DELIVERABLE =
            new DeliverableChannels(Set.of(Channel.LOG, Channel.SERVICE));

    /** A value no machine clock would produce, so a leaked {@code Instant.now()} is visible. */
    private static final Instant SHARED_NOW = Instant.parse("2026-09-03T10:00:00Z");

    private final InMemoryNotifications repository = new InMemoryNotifications();
    private final RecordingIntakeEvents events = new RecordingIntakeEvents();
    private final SubmitNotificationService service =
            new SubmitNotificationService(
                    repository, () -> SHARED_NOW, MAX_ATTEMPTS, DELIVERABLE, events);

    @Test
    @DisplayName("refuses a channel nothing can deliver, and writes no row")
    void refusesAnUndeliverableChannel() {
        // EMAIL is a legal domain value with no adapter in this deployment: accepting it would
        // answer 202 and then fail every attempt until the budget ran out.
        NotificationPayload toEmail =
                new NotificationPayload(
                        "someone@example.test",
                        Channel.EMAIL,
                        "Your order shipped",
                        "It is on its way.",
                        Priority.HIGH,
                        Map.of());

        assertThatExceptionOfType(UndeliverableChannelException.class)
                .isThrownBy(
                        () ->
                                service.submit(
                                        new SubmitNotificationCommand(
                                                toEmail, CORRELATION_ID, "key-email")))
                .satisfies(
                        thrown -> {
                            assertThat(thrown.channel()).isEqualTo(Channel.EMAIL);
                            // The order is stable, so the caller reads the same list every time.
                            assertThat(thrown.deliverable().describe()).isEqualTo("LOG, SERVICE");
                        });

        assertThat(repository.stored())
                .as("nothing may be stored for a notification that cannot be sent")
                .isEmpty();
        assertThat(events.accepted).isEmpty();
    }

    @Test
    @DisplayName("reports the notification it saved as accepted, once")
    void reportsAFreshSubmitAsAccepted() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        assertThat(events.accepted).containsExactly(repository.only());
    }

    @Test
    @DisplayName("does not report a replayed key as accepted again")
    void doesNotReportAReplayAsAccepted() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        assertThat(events.accepted).hasSize(1);
    }

    @Test
    @DisplayName("stores the notification and answers with its identifier")
    void storesAndAnswersWithTheIdentifier() {
        UUID id = service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        assertThat(repository.stored()).hasSize(1);
        assertThat(repository.only().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("hands the domain exactly what the caller asked for")
    void passesTheCallerIntentThrough() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        Notification stored = repository.only();
        assertThat(stored.payload()).isEqualTo(payload());
        assertThat(stored.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(stored.idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("creates it PENDING and claimable, so the dispatcher needs no second signal")
    void createsItQueued() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        Notification stored = repository.only();
        assertThat(stored.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(stored.createdAt());
    }

    @Test
    @DisplayName("stamps the shared clock's instant, not this machine's")
    void stampsTheSharedClock() {
        // The claim query compares next_attempt_at against the database's own now(); written
        // from the JVM, an instance running ahead would queue work nobody may take yet.
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-clock"));

        Notification stored = repository.only();
        assertThat(stored.createdAt()).isEqualTo(SHARED_NOW);
        assertThat(stored.updatedAt()).isEqualTo(SHARED_NOW);
        assertThat(stored.nextAttemptAt()).isEqualTo(SHARED_NOW);
    }

    @Test
    @DisplayName("stamps the configured budget on the row, not a number of its own")
    void stampsTheConfiguredBudget() {
        // Kept even if configuration changes while the notification is still in flight.
        SubmitNotificationService withADifferentBudget =
                new SubmitNotificationService(repository, () -> SHARED_NOW, 7, DELIVERABLE, events);

        withADifferentBudget.submit(
                new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-1"));

        assertThat(repository.only().maxAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("accepts a notification carrying no idempotency key")
    void acceptsAMissingKey() {
        UUID id = service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, null));

        assertThat(id).isNotNull();
        assertThat(repository.only().idempotencyKey()).isNull();
    }

    @Test
    @DisplayName("answers a repeated key with the identifier the first request created")
    void answersARepeatedKeyWithTheOriginalIdentifier() {
        UUID first =
                service.submit(
                        new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        UUID second =
                service.submit(
                        new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("stores nothing the second time, which is the point of the whole mechanism")
    void storesNothingOnARepeatedKey() {
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));

        assertThat(repository.stored()).hasSize(1);
    }

    @Test
    @DisplayName("lets two notifications of one operation through, each with its own key")
    void acceptsTwoNotificationsOfTheSameOperation() {
        // Correlation id is shared across an operation's notifications; idempotency key never is.
        // Confusing the two would silently drop every notification after the first.
        UUID toTheBuyer =
                service.submit(
                        new SubmitNotificationCommand(
                                payload(), CORRELATION_ID, "key-to-the-buyer"));
        UUID toTheWarehouse =
                service.submit(
                        new SubmitNotificationCommand(
                                payload(), CORRELATION_ID, "key-to-the-warehouse"));

        assertThat(toTheBuyer).isNotEqualTo(toTheWarehouse);
        assertThat(repository.stored()).hasSize(2);
    }

    @Test
    @DisplayName("refuses to answer when the notification that rejected the insert cannot be read")
    void refusesToInventAnAnswer() {
        // Nothing deletes a row, so a taken key with no row behind it is corruption, not a
        // recoverable race.
        service.submit(new SubmitNotificationCommand(payload(), CORRELATION_ID, "key-repeated"));
        repository.hideEverything();

        assertThatExceptionOfType(IdempotencyKeyAlreadyUsedException.class)
                .isThrownBy(
                        () ->
                                service.submit(
                                        new SubmitNotificationCommand(
                                                payload(), CORRELATION_ID, "key-repeated")));
    }

    @Test
    @DisplayName("requires a command, and says so rather than failing wherever it first reads one")
    void requiresACommand() {
        // The message is what this test proves: without the guard, the next statement would still
        // throw NPE, so an unguarded assertThatNullPointerException would pass and prove nothing.
        assertThatNullPointerException()
                .isThrownBy(() -> service.submit(null))
                .withMessage("command must not be null");
    }

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "https://destination.test/hook",
                Channel.SERVICE,
                "Your order shipped",
                "It is on its way.",
                Priority.HIGH,
                Map.of("orderId", "4471"));
    }

    private static final class RecordingIntakeEvents implements IntakeEvents {

        private final List<Notification> accepted = new ArrayList<>();

        @Override
        public void accepted(Notification notification) {
            accepted.add(notification);
        }
    }

    /** Storage reduced to the one rule that matters: a repeated idempotency key is refused. */
    private static final class InMemoryNotifications implements NotificationRepository {

        private final Map<UUID, Notification> byId = new LinkedHashMap<>();
        private final Map<String, Notification> byKey = new HashMap<>();
        private boolean hidden;

        @Override
        public void save(Notification notification) {
            String key = notification.idempotencyKey();
            if (key != null && byKey.containsKey(key)) {
                throw new IdempotencyKeyAlreadyUsedException(
                        key, new IllegalStateException("unique index"));
            }
            byId.put(notification.id(), notification);
            if (key != null) {
                byKey.put(key, notification);
            }
        }

        @Override
        public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            return hidden ? Optional.empty() : Optional.ofNullable(byKey.get(idempotencyKey));
        }

        /** Leaves the keys taken but the rows unreadable, which is what corruption looks like. */
        void hideEverything() {
            this.hidden = true;
        }

        Map<UUID, Notification> stored() {
            return byId;
        }

        Notification only() {
            assertThat(byId).hasSize(1);
            return byId.values().iterator().next();
        }
    }
}
