package io.github.iaarencibia.notifications.config;

import io.github.iaarencibia.notifications.adapter.out.observability.CompositeDispatchEvents;
import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.dispatch.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.observe.ObserveQueueDepthUseCase;
import io.github.iaarencibia.notifications.application.port.in.query.GetNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.in.release.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import io.github.iaarencibia.notifications.application.port.out.AttemptHistory;
import io.github.iaarencibia.notifications.application.port.out.DestinationPolicy;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.application.port.out.IntakeEvents;
import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.application.port.out.NotificationDispatchQueue;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.application.port.out.QueueDepthEvents;
import io.github.iaarencibia.notifications.application.port.out.QueueInspection;
import io.github.iaarencibia.notifications.application.port.out.SystemClock;
import io.github.iaarencibia.notifications.application.service.dispatch.DispatchNotificationsService;
import io.github.iaarencibia.notifications.application.service.observe.ObserveQueueDepthService;
import io.github.iaarencibia.notifications.application.service.query.GetNotificationService;
import io.github.iaarencibia.notifications.application.service.release.ReleaseAbandonedClaimsService;
import io.github.iaarencibia.notifications.application.service.submit.SubmitNotificationService;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.RetryPolicy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Where the use cases are assembled, wired from here rather than annotated with {@code @Service} so
 * the application layer carries no framework import and takes its configuration as plain values.
 * Dependencies point one way: this package knows the use cases, not the reverse.
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    /**
     * Names the delivery pool at both ends. It is not the only {@code Executor} in the context --
     * scheduling brings its own -- and it must never be resolved by type.
     */
    private static final String DISPATCH_WORKERS = "dispatchWorkers";

    @Bean
    SubmitNotificationUseCase submitNotificationUseCase(
            DeliverableChannels deliverableChannels,
            DestinationPolicy destinations,
            NotificationRepository repository,
            SystemClock clock,
            NotificationsProperties properties,
            IntakeEvents events) {
        return new SubmitNotificationService(
                deliverableChannels,
                destinations,
                repository,
                clock,
                properties.retry().maxAttempts(),
                events);
    }

    /**
     * The schedule every reschedule follows, built once. Its own constructor refuses an impossible
     * one -- a multiplier that shrinks the delay, a ceiling below the first backoff -- so building
     * it here means that refusal lands while the context starts.
     */
    @Bean
    RetryPolicy retryPolicy(NotificationsProperties properties) {
        NotificationsProperties.Retry retry = properties.retry();
        return new RetryPolicy(
                retry.initialBackoff(), retry.multiplier(), retry.maxBackoff(), retry.jitter());
    }

    @Bean
    DispatchDueNotificationsUseCase dispatchDueNotificationsUseCase(
            NotificationDispatchQueue queue,
            NotificationsProperties properties,
            List<DispatchEvents> events,
            @Qualifier(DISPATCH_WORKERS) Executor workers,
            SystemClock clock,
            Map<Channel, NotificationChannelSender> senders,
            RetryPolicy retryPolicy) {
        return new DispatchNotificationsService(
                queue,
                properties.dispatch().batchSize(),
                properties.instanceId(),
                new CompositeDispatchEvents(events),
                workers,
                clock,
                System::nanoTime,
                senders,
                retryPolicy);
    }

    @Bean
    ReleaseAbandonedClaimsUseCase releaseAbandonedClaimsUseCase(
            AbandonedClaims abandonedClaims, NotificationsProperties properties) {
        return new ReleaseAbandonedClaimsService(
                abandonedClaims, properties.reaper().staleClaimTimeout());
    }

    @Bean
    ObserveQueueDepthUseCase observeQueueDepthUseCase(
            QueueInspection queue, QueueDepthEvents events) {
        return new ObserveQueueDepthService(queue, events);
    }

    @Bean
    GetNotificationUseCase getNotificationUseCase(
            NotificationRepository repository, AttemptHistory history) {
        return new GetNotificationService(repository, history);
    }

    /**
     * The pool the deliveries run on. Bounded in both dimensions on purpose: an unbounded pool
     * turns a slow destination into unbounded threads, and an unbounded queue turns it into
     * unbounded memory.
     *
     * <p>Not a default candidate: scheduling contributes an {@code Executor} of its own, so by type
     * alone there is no answer, and this one's bounds are chosen for outbound deliveries only.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(DISPATCH_WORKERS)
    Executor dispatchWorkers(NotificationsProperties properties) {
        ThreadPoolTaskExecutor workers = new ThreadPoolTaskExecutor();
        workers.setCorePoolSize(properties.dispatch().workerPoolSize());
        workers.setMaxPoolSize(properties.dispatch().workerPoolSize());
        workers.setQueueCapacity(properties.dispatch().workerQueueCapacity());
        workers.setThreadNamePrefix("dispatch-");
        // With the queue full, the polling thread performs the delivery itself -- the
        // backpressure: while it is busy delivering, it is not claiming more work into memory.
        workers.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // A claimed notification dropped at shutdown stays DISPATCHING until the reaper releases
        // it, minutes later. Finishing what is in flight costs seconds.
        workers.setWaitForTasksToCompleteOnShutdown(true);
        workers.setAwaitTerminationSeconds(30);
        workers.initialize();
        return workers;
    }

    @Bean
    Map<Channel, NotificationChannelSender> channelSenders(
            List<NotificationChannelSender> senders) {
        Map<Channel, NotificationChannelSender> byChannel = new EnumMap<>(Channel.class);
        for (NotificationChannelSender sender : senders) {
            if (byChannel.putIfAbsent(sender.channel(), sender) != null) {
                // Refused at startup rather than resolved silently: with two senders claiming one
                // channel, which delivers would be settled by the order Spring returns the beans
                // in, and nobody chose that.
                throw new IllegalStateException(
                        "more than one sender is registered for channel " + sender.channel());
            }
        }
        return Map.copyOf(byChannel);
    }

    /**
     * What this deployment can send, derived from the adapters actually present rather than a list
     * kept in step by hand -- the failure this shape exists to prevent. A bean, not a private
     * helper, so the web adapter gets the same answer when it tells a caller what it may send.
     */
    @Bean
    DeliverableChannels deliverableChannels(
            Map<Channel, NotificationChannelSender> channelSenders) {
        return new DeliverableChannels(channelSenders.keySet());
    }
}
