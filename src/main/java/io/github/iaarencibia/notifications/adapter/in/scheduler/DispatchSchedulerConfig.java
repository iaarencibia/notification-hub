package io.github.iaarencibia.notifications.adapter.in.scheduler;

import io.github.iaarencibia.notifications.application.port.in.dispatch.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.observe.ObserveQueueDepthUseCase;
import io.github.iaarencibia.notifications.application.port.in.release.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The inbound adapter that drives the dispatcher: a timer, and nothing else.
 *
 * <p>Intervals come from the bound configuration rather than {@code @Scheduled} annotations, so the
 * scheduler runs on the same validated values the rest of the application reads instead of
 * resolving the placeholder a second time.
 *
 * <p>Fixed delay, not fixed rate: the next pass starts after the previous one returns, so a slow
 * pass delays the next instead of overlapping it. Each pass gets its own thread ({@code
 * spring.task.scheduling.pool.size}), so a saturated dispatch pass cannot also stall the reaper or
 * the queue depth refresh.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "notifications.dispatch",
        name = "scheduled",
        havingValue = "true",
        matchIfMissing = true)
class DispatchSchedulerConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DispatchSchedulerConfig.class);

    // Logged on the edge: with the database down, every tick would otherwise add a stack trace.
    private final Set<String> failingPasses = ConcurrentHashMap.newKeySet();

    private final DispatchDueNotificationsUseCase dispatchDueNotifications;
    private final ReleaseAbandonedClaimsUseCase releaseAbandonedClaims;
    private final ObserveQueueDepthUseCase observeQueueDepth;
    private final NotificationsProperties properties;
    private final Counter releasedClaims;

    DispatchSchedulerConfig(
            DispatchDueNotificationsUseCase dispatchDueNotifications,
            ReleaseAbandonedClaimsUseCase releaseAbandonedClaims,
            ObserveQueueDepthUseCase observeQueueDepth,
            NotificationsProperties properties,
            MeterRegistry registry) {
        this.dispatchDueNotifications = dispatchDueNotifications;
        this.releaseAbandonedClaims = releaseAbandonedClaims;
        this.observeQueueDepth = observeQueueDepth;
        this.properties = properties;
        this.releasedClaims = registry.counter("notifications.claims.released");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::pollForDueWork, properties.dispatch().pollInterval());
        registrar.addFixedDelayTask(this::recoverAbandonedWork, properties.reaper().interval());
        registrar.addFixedDelayTask(
                this::refreshQueueDepth, properties.queueMetrics().refreshInterval());
    }

    private void pollForDueWork() {
        run("dispatch", dispatchDueNotifications::dispatchDue);
    }

    private void recoverAbandonedWork() {
        int released = run("reaper", releaseAbandonedClaims::releaseAbandonedClaims);
        if (released > 0) {
            releasedClaims.increment(released);
            // Worth a line of its own: on a healthy system this never fires, so when it does it
            // says an instance died mid-delivery and those notifications may be delivered twice.
            log.warn("released {} abandoned claims", released);
        }
    }

    private void refreshQueueDepth() {
        run("queue depth", observeQueueDepth::observeQueueDepth);
    }

    private void run(String pass, Runnable work) {
        run(
                pass,
                () -> {
                    work.run();
                    return 0;
                });
    }

    // Keeps the timer alive across a failed pass: a briefly unreachable database must not end
    // the schedule for the life of the process, so this pass is lost and the next one is not.
    private int run(String pass, IntSupplier work) {
        try {
            int result = work.getAsInt();
            if (failingPasses.remove(pass)) {
                log.info("the {} pass recovered", pass);
            }
            return result;
        } catch (RuntimeException failure) {
            if (failingPasses.add(pass)) {
                log.warn("the {} pass failed and will be retried on the next tick", pass, failure);
            } else {
                log.debug("the {} pass is still failing: {}", pass, failure.toString());
            }
            return 0;
        }
    }
}
