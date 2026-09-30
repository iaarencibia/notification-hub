package io.github.iaarencibia.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That each configured value reaches the argument it belongs to.
 *
 * <p>Both bean methods hand over several values of the same type -- three {@code int}s to the
 * dispatcher, three {@code Duration}s within reach of the reaper -- so passing the wrong one
 * compiles and changes nothing visible until the service is running. Crossing the reaper's is the
 * expensive mistake: given the poll interval instead of its threshold, it would release claims
 * after a second and deliver every slow notification twice.
 */
class DispatchWiringTest {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration REAPER_INTERVAL = Duration.ofMinutes(1);
    private static final Duration STALE_CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 25;
    private static final String INSTANCE_ID = "notification-service-1";

    private final UseCaseConfig config = new UseCaseConfig();
    private final RecordingQueue queue = new RecordingQueue();
    private final RecordingClaims claims = new RecordingClaims();

    @Test
    @DisplayName("gives the reaper its own threshold, not one of the intervals beside it")
    void wiresTheStaleClaimTimeout() {
        config.releaseAbandonedClaimsUseCase(claims, properties()).releaseAbandonedClaims();

        assertThat(claims.releasedOlderThan)
                .as("the poll interval and the reaper interval are also Durations in reach here")
                .isEqualTo(STALE_CLAIM_TIMEOUT)
                .isNotEqualTo(POLL_INTERVAL)
                .isNotEqualTo(REAPER_INTERVAL);
    }

    @Test
    @DisplayName("gives the dispatcher its batch size and this instance's identity")
    void wiresTheBatchSizeAndInstanceId() {
        config.dispatchDueNotificationsUseCase(
                        queue,
                        Map.of(),
                        List.of(new NoOpEvents()),
                        () -> Instant.EPOCH,
                        new RetryPolicy(Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2),
                        Runnable::run,
                        properties())
                .dispatchDue();

        assertThat(queue.batchSize).isEqualTo(BATCH_SIZE);
        assertThat(queue.claimedBy)
                .as("the reaper traces abandoned work by this value")
                .isEqualTo(INSTANCE_ID);
    }

    /** Every number differs from every other, so a crossed pair changes the result. */
    private static NotificationsProperties properties() {
        return new NotificationsProperties(
                INSTANCE_ID,
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Dispatch(POLL_INTERVAL, BATCH_SIZE, 8, 100),
                new NotificationsProperties.Reaper(STALE_CLAIM_TIMEOUT, REAPER_INTERVAL),
                new NotificationsProperties.Retry(
                        3, Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(
                                Duration.ofSeconds(2), Duration.ofSeconds(5), List.of())));
    }

    /** Records what the dispatcher asked it to claim. */
    private static final class RecordingQueue implements NotificationDispatchQueue {

        private int batchSize;
        private String claimedBy;

        @Override
        public List<ClaimedNotification> claimDue(int batchSize, String claimedBy) {
            this.batchSize = batchSize;
            this.claimedBy = claimedBy;
            return List.of();
        }

        @Override
        public void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt) {
            throw new UnsupportedOperationException("nothing was claimed to report on");
        }
    }

    /** Records the threshold the reaper released against. */
    private static final class RecordingClaims implements AbandonedClaims {

        private Duration releasedOlderThan;

        @Override
        public int release(Duration staleAfter) {
            this.releasedOlderThan = staleAfter;
            return 0;
        }
    }

    private static final class NoOpEvents implements DispatchEvents {

        @Override
        public void claimed(Notification notification) {}

        @Override
        public void dispatched(Notification notification, NotificationAttempt attempt) {}

        @Override
        public void claimLost(Notification notification, ClaimLostException cause) {}

        @Override
        public void failed(Notification notification, RuntimeException cause) {}
    }
}
