package io.github.iaarencibia.notifications.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.out.AttemptHistory;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import io.github.iaarencibia.notifications.domain.Priority;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The persistence adapter against the real schema, on a real PostgreSQL. Half of what is under test
 * here is enforced by the database, not Java: the partial unique index on the idempotency key, the
 * check constraint that forbids a claimed row without an owner, and the JSONB column the metadata
 * lands in. An in-memory substitute would agree with whatever the mapper did.
 *
 * <p>The rehydration rows are written with SQL rather than through the repository: it gives every
 * column a distinct value, which is what makes a crossed pair of same-typed fields visible, and it
 * avoids growing an update path nothing yet calls -- the intake endpoint only ever inserts.
 */
@SpringBootTest
class NotificationRepositoryIT extends IntegrationTestSupport {

    @Autowired private NotificationRepository repository;

    @Autowired private AttemptHistory history;

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("reads back a notification it has just stored")
    void storesAndReadsBackANewNotification() {
        Notification submitted =
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        new CorrelationId("order-4471"),
                        "key-new",
                        3,
                        Instant.parse("2026-09-01T10:00:00Z"));

        repository.save(submitted);

        Notification found = repository.findByIdempotencyKey("key-new").orElseThrow();
        assertThat(found.id()).isEqualTo(submitted.id());
        assertThat(found.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(found.attempts()).isZero();
        assertThat(found.payload()).isEqualTo(payload());
        assertThat(found.correlationId()).isEqualTo(new CorrelationId("order-4471"));
    }

    @Test
    @DisplayName("writes the budget the aggregate carries, not a number of its own")
    void writesTheBudgetTheAggregateCarries() {
        // Five, deliberately not the three the application is configured with: an assertion
        // against the configured default is satisfied by a mapper writing that same constant,
        // so it cannot tell the two apart.
        Notification submitted =
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        new CorrelationId("order-4473"),
                        "key-budget",
                        5,
                        Instant.parse("2026-09-01T13:00:00Z"));

        repository.save(submitted);

        // Read as a column rather than through findByIdempotencyKey. A round trip proves only
        // that the two directions agree, which they would even if both used the wrong field.
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT max_attempts FROM notification WHERE idempotency_key = 'key-budget'");
        assertThat(row.get("max_attempts")).isEqualTo(5);
    }

    @Test
    @DisplayName("reads back a notification that is in flight, with the history it already has")
    void readsBackANotificationInFlight() {
        // Every value is different from every other, on purpose -- including created_at and
        // updated_at, which a real row of this age would carry equal. Two Instants that happened
        // to match would let the mapper swap them and still pass.
        UUID id = UUID.randomUUID();
        insertRow(
                id,
                "key-in-flight",
                "order-9001",
                NotificationStatus.DISPATCHING,
                2,
                3,
                Instant.parse("2026-09-01T11:00:00Z"), // created_at
                Instant.parse("2026-09-01T11:07:00Z"), // updated_at
                Instant.parse("2026-09-01T11:05:00Z"), // next_attempt_at
                Instant.parse("2026-09-01T11:06:00Z"), // claimed_at
                "notification-service-7", // claimed_by
                null, // sent_at
                "503 from the destination"); // last_error

        Notification found = repository.findByIdempotencyKey("key-in-flight").orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.status()).isEqualTo(NotificationStatus.DISPATCHING);
        assertThat(found.attempts()).isEqualTo(2);
        assertThat(found.maxAttempts()).isEqualTo(3);
        assertThat(found.correlationId()).isEqualTo(new CorrelationId("order-9001"));
        assertThat(found.createdAt()).isEqualTo(Instant.parse("2026-09-01T11:00:00Z"));
        assertThat(found.updatedAt()).isEqualTo(Instant.parse("2026-09-01T11:07:00Z"));
        assertThat(found.nextAttemptAt()).isEqualTo(Instant.parse("2026-09-01T11:05:00Z"));
        assertThat(found.claimedAt()).isEqualTo(Instant.parse("2026-09-01T11:06:00Z"));
        assertThat(found.claimedBy()).isEqualTo("notification-service-7");
        assertThat(found.sentAt()).isNull();
        assertThat(found.lastError()).isEqualTo("503 from the destination");
    }

    @Test
    @DisplayName("reads back a delivered notification, which is the only state carrying sent_at")
    void readsBackADeliveredNotification() {
        UUID id = UUID.randomUUID();
        insertRow(
                id,
                "key-delivered",
                "order-9002",
                NotificationStatus.SENT,
                3,
                3,
                Instant.parse("2026-09-01T12:00:00Z"), // created_at
                Instant.parse("2026-09-01T12:03:00Z"), // updated_at
                Instant.parse("2026-09-01T12:01:00Z"), // next_attempt_at
                null, // claimed_at
                null, // claimed_by
                Instant.parse("2026-09-01T12:02:00Z"), // sent_at
                null); // last_error

        Notification found = repository.findByIdempotencyKey("key-delivered").orElseThrow();

        assertThat(found.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(found.updatedAt()).isEqualTo(Instant.parse("2026-09-01T12:03:00Z"));
        assertThat(found.sentAt()).isEqualTo(Instant.parse("2026-09-01T12:02:00Z"));
        assertThat(found.claimedAt()).isNull();
        assertThat(found.claimedBy()).isNull();
        assertThat(found.lastError()).isNull();
    }

    @Test
    @DisplayName("finds nothing for a key that was never used")
    void findsNothingForAnUnusedKey() {
        assertThat(repository.findByIdempotencyKey("key-nobody-sent")).isEmpty();
    }

    @Test
    @DisplayName("refuses a second notification carrying an idempotency key already in use")
    void refusesARepeatedIdempotencyKey() {
        repository.save(
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        new CorrelationId("order-7000"),
                        "key-taken",
                        3,
                        Instant.now()));

        // The exclusion is the database's, not the code's: the partial unique index is what a
        // second concurrent request runs into, and no check-then-write in Java could close that
        // window. What crosses the port is the adapter's own exception.
        assertThatExceptionOfType(IdempotencyKeyAlreadyUsedException.class)
                .isThrownBy(
                        () ->
                                repository.save(
                                        Notification.submit(
                                                UUID.randomUUID(),
                                                payload(),
                                                new CorrelationId("order-7001"),
                                                "key-taken",
                                                3,
                                                Instant.now())))
                .withMessageContaining("key-taken")
                .satisfies(thrown -> assertThat(thrown.idempotencyKey()).isEqualTo("key-taken"));
    }

    @Test
    @DisplayName("refuses a lookup with no key, rather than reporting that nothing matched")
    void refusesALookupWithoutAKey() {
        // A null would compare as SQL null and match nothing, so the caller would be told the
        // notification does not exist when what really happened is that it never asked.
        assertThatNullPointerException().isThrownBy(() -> repository.findByIdempotencyKey(null));
    }

    @Test
    @DisplayName("accepts two notifications of the same operation, each with its own key")
    void acceptsTwoNotificationsOfTheSameOperation() {
        // The case that would break if the two identifiers were ever crossed: a correlation id is
        // shared by every notification of one operation, an idempotency key never is. Sending the
        // correlation id as the key would silently drop all but the first.
        CorrelationId sameOperation = new CorrelationId("order-4471");

        repository.save(
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        sameOperation,
                        "key-to-the-buyer",
                        3,
                        Instant.now()));
        repository.save(
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        sameOperation,
                        "key-to-the-warehouse",
                        3,
                        Instant.now()));

        // Asserted row by row, not by counting rows that carry the correlation id: a count
        // would couple this test to every other one using the same identifier, and an
        // operation may hold any number of notifications.
        Notification toTheBuyer = repository.findByIdempotencyKey("key-to-the-buyer").orElseThrow();
        Notification toTheWarehouse =
                repository.findByIdempotencyKey("key-to-the-warehouse").orElseThrow();

        assertThat(toTheBuyer.id()).isNotEqualTo(toTheWarehouse.id());
        assertThat(toTheBuyer.correlationId()).isEqualTo(sameOperation);
        assertThat(toTheWarehouse.correlationId()).isEqualTo(sameOperation);
    }

    @Test
    @DisplayName("finds a notification by its identifier")
    void findsANotificationById() {
        Notification submitted =
                Notification.submit(
                        UUID.randomUUID(),
                        payload(),
                        new CorrelationId("order-4474"),
                        "key-by-id",
                        3,
                        Instant.parse("2026-09-01T14:00:00Z"));
        repository.save(submitted);

        Notification found = repository.findById(submitted.id()).orElseThrow();

        assertThat(found.id()).isEqualTo(submitted.id());
        assertThat(found.payload()).isEqualTo(payload());
        assertThat(found.correlationId()).isEqualTo(new CorrelationId("order-4474"));
        assertThat(found.createdAt()).isEqualTo(Instant.parse("2026-09-01T14:00:00Z"));
    }

    @Test
    @DisplayName("finds nothing for an identifier no notification has")
    void findsNothingForAnUnknownId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName(
            "lists the attempts of a notification oldest first, whatever order they were stored")
    void listsAttemptsOldestFirst() {
        UUID id = insertNotification("key-history");
        UUID other = insertNotification("key-history-other");
        insertAttempt(id, 3, "SUCCESS", 202, null, "2026-09-01T10:03:00Z");
        insertAttempt(
                id,
                1,
                "RETRYABLE_FAILURE",
                503,
                "503 from the destination",
                "2026-09-01T10:01:00Z");
        insertAttempt(id, 2, "RETRYABLE_FAILURE", null, "timed out", "2026-09-01T10:02:00Z");
        insertAttempt(other, 1, "SUCCESS", 200, null, "2026-09-01T10:00:00Z");

        List<NotificationAttempt> attempts = history.attemptsOf(id);

        assertThat(attempts)
                .extracting(NotificationAttempt::attemptNumber)
                .containsExactly(1, 2, 3);
        assertThat(attempts)
                .allSatisfy(attempt -> assertThat(attempt.notificationId()).isEqualTo(id));
        NotificationAttempt first = attempts.get(0);
        assertThat(first.channel()).isEqualTo(Channel.SERVICE);
        assertThat(first.outcome()).isEqualTo(AttemptOutcome.RETRYABLE_FAILURE);
        assertThat(first.responseCode()).isEqualTo(503);
        assertThat(first.errorMessage()).isEqualTo("503 from the destination");
        assertThat(first.startedAt()).isEqualTo(Instant.parse("2026-09-01T10:01:00Z"));
        assertThat(first.finishedAt()).isEqualTo(Instant.parse("2026-09-01T10:01:05Z"));
        assertThat(first.durationMs())
                .as("read back as stored, not recomputed from the two instants")
                .isEqualTo(4200L);
        assertThat(attempts.get(1).responseCode()).isNull();
        assertThat(attempts.get(2).errorMessage()).isNull();
    }

    @Test
    @DisplayName("lists no attempts for a notification that has made none")
    void listsNoAttemptsForAFreshNotification() {
        UUID id = insertNotification("key-no-history");

        assertThat(history.attemptsOf(id)).isEmpty();
    }

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "https://example.test/hook",
                Channel.SERVICE,
                "Your order shipped",
                "It is on its way.",
                Priority.HIGH,
                Map.of("orderId", "4471"));
    }

    /**
     * Writes a row directly, so a state the intake endpoint cannot produce can be read back. The
     * column list is spelled out here, not reused from the mapper: a test that builds its
     * expectation with the code under test proves nothing.
     */
    private void insertRow(
            UUID id,
            String idempotencyKey,
            String correlationId,
            NotificationStatus status,
            int attempts,
            int maxAttempts,
            Instant createdAt,
            Instant updatedAt,
            Instant nextAttemptAt,
            Instant claimedAt,
            String claimedBy,
            Instant sentAt,
            String lastError) {
        jdbc.update(
                """
                INSERT INTO notification (
                    id, recipient, channel, subject, body, priority, metadata,
                    status, attempts, max_attempts, next_attempt_at, last_error,
                    claimed_at, claimed_by, correlation_id, idempotency_key,
                    created_at, updated_at, sent_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                id,
                "https://example.test/hook",
                "SERVICE",
                "Your order shipped",
                "It is on its way.",
                "HIGH",
                "{\"orderId\": \"4471\"}",
                status.name(),
                attempts,
                maxAttempts,
                timestamp(nextAttemptAt),
                lastError,
                timestamp(claimedAt),
                claimedBy,
                correlationId,
                idempotencyKey,
                timestamp(createdAt),
                timestamp(updatedAt),
                timestamp(sentAt));
    }

    private UUID insertNotification(String idempotencyKey) {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-09-01T09:00:00Z");
        insertRow(
                id,
                idempotencyKey,
                "order-9003",
                NotificationStatus.PENDING,
                0,
                3,
                created,
                created,
                created,
                null,
                null,
                null,
                null);
        return id;
    }

    private void insertAttempt(
            UUID notificationId,
            int number,
            String outcome,
            Integer responseCode,
            String errorMessage,
            String startedAt) {
        Instant started = Instant.parse(startedAt);
        jdbc.update(
                """
                INSERT INTO notification_attempt (
                    id, notification_id, attempt_number, channel, outcome, response_code,
                    error_message, started_at, finished_at, duration_ms)
                VALUES (?, ?, ?, 'SERVICE', ?, ?, ?, ?, ?, 4200)
                """,
                UUID.randomUUID(),
                notificationId,
                number,
                outcome,
                responseCode,
                errorMessage,
                timestamp(started),
                timestamp(started.plusSeconds(5)));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
