package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.QueueDepth;
import io.github.iaarencibia.notifications.application.port.out.QueueInspection;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.NotificationStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The outbound side of dispatch: claiming work, reporting on it, and recovering what was abandoned.
 *
 * <p>Sits outside {@link NotificationJpaStore}, which only issues statements: what a statement that
 * matched no row means to the port is decided here.
 *
 * <p>Answers three ports, not one, because all are statements against the same table; the
 * separation that matters is on the calling side, where the reaper holds only {@link
 * AbandonedClaims}.
 */
@Component
class NotificationDispatchAdapter
        implements NotificationDispatchQueue, AbandonedClaims, QueueInspection {

    private final NotificationJpaStore store;

    NotificationDispatchAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public List<ClaimedNotification> claimDue(int batchSize, String claimedBy) {
        return store.claimDue(batchSize, claimedBy).stream()
                .map(
                        entity ->
                                new ClaimedNotification(
                                        NotificationMapper.toDomain(entity), entity.getVersion()))
                .toList();
    }

    @Override
    public void recordAttempt(ClaimedNotification claimed, NotificationAttempt attempt) {
        // The version the row carried when it was claimed, not the one it carries now: written
        // against the current value, the guard would pass every time and protect nothing.
        boolean claimHeld =
                store.recordAttempt(
                        claimed.notification(),
                        claimed.version(),
                        NotificationMapper.toEntity(attempt));

        if (!claimHeld) {
            throw new ClaimLostException(claimed.notification().id());
        }
    }

    @Override
    public int release(Duration staleAfter) {
        return store.releaseStaleClaims(staleAfter);
    }

    @Override
    public QueueDepth measure() {
        Map<String, Long> counts = store.countQueued();
        return new QueueDepth(
                counts.getOrDefault(NotificationStatus.PENDING.name(), 0L),
                counts.getOrDefault(NotificationStatus.DISPATCHING.name(), 0L));
    }
}
