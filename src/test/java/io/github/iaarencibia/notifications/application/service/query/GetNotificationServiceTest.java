package io.github.iaarencibia.notifications.application.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.iaarencibia.notifications.application.port.in.query.NotificationDetails;
import io.github.iaarencibia.notifications.application.port.in.query.NotificationNotFoundException;
import io.github.iaarencibia.notifications.application.port.out.AttemptHistory;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetNotificationServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final FakeHistory history = new FakeHistory();
    private final GetNotificationService service = new GetNotificationService(repository, history);

    @Test
    @DisplayName("returns the notification together with its attempts")
    void returnsTheNotificationWithItsAttempts() {
        Notification notification = notification();
        NotificationAttempt first = attempt(notification.id(), 1);
        NotificationAttempt second = attempt(notification.id(), 2);
        repository.stored = notification;
        history.attempts = List.of(first, second);

        NotificationDetails details = service.get(notification.id());

        assertThat(details.notification()).isSameAs(notification);
        assertThat(details.attempts()).containsExactly(first, second);
        assertThat(history.askedAbout).isEqualTo(notification.id());
    }

    @Test
    @DisplayName("returns an empty history for a notification that was never attempted")
    void returnsAnEmptyHistory() {
        Notification notification = notification();
        repository.stored = notification;

        assertThat(service.get(notification.id()).attempts()).isEmpty();
    }

    @Test
    @DisplayName("refuses an identifier no notification has, naming it, and reads no history")
    void refusesAnUnknownId() {
        UUID unknown = UUID.randomUUID();

        assertThatExceptionOfType(NotificationNotFoundException.class)
                .isThrownBy(() -> service.get(unknown))
                .satisfies(thrown -> assertThat(thrown.id()).isEqualTo(unknown));
        assertThat(history.askedAbout).isNull();
    }

    @Test
    @DisplayName("refuses to be asked for a notification with no identifier")
    void refusesAMissingId() {
        assertThatNullPointerException().isThrownBy(() -> service.get(null));
    }

    private static Notification notification() {
        return Notification.submit(
                UUID.randomUUID(),
                new NotificationPayload(
                        "https://example.test/hook",
                        Channel.SERVICE,
                        "a subject",
                        "a body",
                        Priority.MEDIUM,
                        Map.of()),
                new CorrelationId("order-4471"),
                "key-1",
                3,
                Instant.parse("2026-09-01T10:00:00Z"));
    }

    private static NotificationAttempt attempt(UUID notificationId, int number) {
        Instant at = Instant.parse("2026-09-01T10:00:00Z").plusSeconds(number);
        return new NotificationAttempt(
                UUID.randomUUID(),
                notificationId,
                number,
                Channel.SERVICE,
                AttemptOutcome.SUCCESS,
                202,
                null,
                at,
                at,
                0L);
    }

    private static final class FakeRepository implements NotificationRepository {

        private Notification stored;

        @Override
        public void save(Notification notification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(stored).filter(found -> found.id().equals(id));
        }
    }

    private static final class FakeHistory implements AttemptHistory {

        private List<NotificationAttempt> attempts = new ArrayList<>();
        private UUID askedAbout;

        @Override
        public List<NotificationAttempt> attemptsOf(UUID notificationId) {
            this.askedAbout = notificationId;
            return attempts;
        }
    }
}
