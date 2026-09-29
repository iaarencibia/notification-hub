package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.in.UndeliverableChannelException;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Notification;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts a notification and, by the same write, queues it. Not transactional: a single write, with
 * nothing to hold together.
 */
public class SubmitNotificationService implements SubmitNotificationUseCase {

    private final NotificationRepository repository;
    private final SystemClock clock;
    private final int maxAttempts;
    private final DeliverableChannels deliverableChannels;
    private final IntakeEvents events;

    /**
     * @param clock the shared clock; the claim query compares against the same source
     */
    public SubmitNotificationService(
            NotificationRepository repository,
            SystemClock clock,
            int maxAttempts,
            DeliverableChannels deliverableChannels,
            IntakeEvents events) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxAttempts = maxAttempts;
        this.deliverableChannels =
                Objects.requireNonNull(deliverableChannels, "deliverableChannels must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    @Override
    public UUID submit(SubmitNotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Refused at intake: accepting it would answer 202 and then fail every attempt.
        Channel channel = command.payload().channel();
        if (!deliverableChannels.includes(channel)) {
            throw new UndeliverableChannelException(channel, deliverableChannels);
        }

        // The shared clock: next_attempt_at is compared against the database's own now(), so an
        // instant read from this JVM could queue work nobody may take yet.
        Instant now = clock.now();

        Notification notification =
                Notification.submit(
                        UUID.randomUUID(),
                        command.payload(),
                        command.correlationId(),
                        command.idempotencyKey(),
                        maxAttempts,
                        now);

        try {
            repository.save(notification);
            events.accepted(notification);
            return notification.id();
        } catch (IdempotencyKeyAlreadyUsedException alreadyUsed) {
            // Not a failure: the caller gets back the identifier its first attempt created.
            return repository
                    .findByIdempotencyKey(alreadyUsed.idempotencyKey())
                    .map(Notification::id)
                    // A taken key with no row behind it is corruption, not a race.
                    .orElseThrow(() -> alreadyUsed);
        }
    }
}
