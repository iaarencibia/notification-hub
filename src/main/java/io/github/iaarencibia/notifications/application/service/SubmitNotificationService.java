package io.github.iaarencibia.notifications.application.service;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.IdempotencyKeyReusedException;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.in.UnacceptableRecipientException;
import io.github.iaarencibia.notifications.application.port.in.UndeliverableChannelException;
import io.github.iaarencibia.notifications.application.port.out.DestinationPolicy;
import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Notification;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
    private final DestinationPolicy destinations;

    /**
     * @param clock the shared clock; the claim query compares against the same source
     */
    public SubmitNotificationService(
            NotificationRepository repository,
            SystemClock clock,
            int maxAttempts,
            DeliverableChannels deliverableChannels,
            IntakeEvents events,
            DestinationPolicy destinations) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxAttempts = maxAttempts;
        this.deliverableChannels =
                Objects.requireNonNull(deliverableChannels, "deliverableChannels must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.destinations = Objects.requireNonNull(destinations, "destinations must not be null");
    }

    @Override
    public UUID submit(SubmitNotificationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Refused at intake: accepting it would answer 202 and then fail every attempt.
        Channel channel = command.payload().channel();
        if (!deliverableChannels.includes(channel)) {
            throw new UndeliverableChannelException(channel, deliverableChannels);
        }

        String recipient = command.payload().recipient();
        Optional<String> refusal = destinations.refuse(channel, recipient);
        if (refusal.isPresent()) {
            return replayOf(command)
                    .orElseThrow(() -> new UnacceptableRecipientException(refusal.get()));
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
            // A repeat of the same payload is not a failure: the caller gets its first identifier
            // back.
            Notification existing =
                    repository
                            .findByIdempotencyKey(alreadyUsed.idempotencyKey())
                            // A taken key with no row behind it is corruption, not a race.
                            .orElseThrow(() -> alreadyUsed);
            if (!existing.payload().equals(command.payload())) {
                throw new IdempotencyKeyReusedException(
                        alreadyUsed.idempotencyKey(), existing.id());
            }
            return existing.id();
        }
    }

    private Optional<UUID> replayOf(SubmitNotificationCommand command) {
        String key = command.idempotencyKey();
        if (key == null) {
            return Optional.empty();
        }
        return repository
                .findByIdempotencyKey(key)
                .map(
                        existing -> {
                            if (!existing.payload().equals(command.payload())) {
                                throw new IdempotencyKeyReusedException(key, existing.id());
                            }
                            return existing.id();
                        });
    }
}
