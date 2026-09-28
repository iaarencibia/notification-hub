package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.port.in.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.ClaimedNotification;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pass of the dispatcher. Claiming a batch and recording each result are separate transactions;
 * the delivery itself, between them, holds no database connection.
 */
public final class DispatchNotificationsService implements DispatchDueNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationsService.class);

    private final NotificationDispatchQueue queue;
    private final Map<Channel, NotificationChannelSender> senders;
    private final SystemClock clock;
    private final RetryPolicy retryPolicy;
    private final Executor deliveries;
    private final String instanceId;
    private final int batchSize;

    /**
     * @param deliveries bounded, so a slow destination costs a fixed number of threads rather than
     *     one per notification
     */
    public DispatchNotificationsService(
            NotificationDispatchQueue queue,
            Map<Channel, NotificationChannelSender> senders,
            SystemClock clock,
            RetryPolicy retryPolicy,
            Executor deliveries,
            String instanceId,
            int batchSize) {

        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.senders = Map.copyOf(Objects.requireNonNull(senders, "senders must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int dispatchDue() {
        List<ClaimedNotification> claimed = queue.claimDue(batchSize, instanceId);
        for (ClaimedNotification each : claimed) {
            deliveries.execute(() -> deliver(each));
        }
        return claimed.size();
    }

    // Nothing may escape here: the executor's default handler would just log the throwable and
    // leave the row claimed forever.
    private void deliver(ClaimedNotification claimed) {
        Notification notification = claimed.notification();
        try {
            Instant startedAt = clock.now();
            DispatchOutcome outcome = attemptDelivery(notification);
            Instant finishedAt = clock.now();

            NotificationAttempt attempt =
                    notification.recordAttempt(outcome, startedAt, finishedAt, retryPolicy);
            queue.recordAttempt(new ClaimedNotification(notification, claimed.version()), attempt);

            log.debug(
                    "dispatched notification {} over {}: {}",
                    notification.id(),
                    notification.payload().channel(),
                    attempt.outcome());
        } catch (ClaimLostException lostRace) {
            // Expected: the reaper already released this claim, so the write was refused as stale.
            log.info(
                    "discarded the result of notification {}: {}",
                    notification.id(),
                    lostRace.getMessage());
        } catch (RuntimeException unexpected) {
            // The row stays claimed until the reaper releases it; an operator needs to know that.
            log.error(
                    "failed to dispatch notification {}; it stays claimed until the reaper "
                            + "releases it",
                    notification.id(),
                    unexpected);
        }
    }

    private DispatchOutcome attemptDelivery(Notification notification) {
        Channel channel = notification.payload().channel();
        NotificationChannelSender sender = senders.get(channel);

        if (sender == null) {
            // Reachable when a deployment claims a row for a channel it no longer has an adapter
            // for. Failed permanently: retrying would still find no adapter.
            return new DispatchOutcome.PermanentFailure(
                    null, "this deployment has no adapter for channel " + channel);
        }
        return sender.send(notification);
    }
}
