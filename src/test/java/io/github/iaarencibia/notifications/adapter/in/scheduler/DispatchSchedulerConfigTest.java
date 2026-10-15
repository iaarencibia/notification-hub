package io.github.iaarencibia.notifications.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.iaarencibia.notifications.application.port.in.dispatch.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.observe.ObserveQueueDepthUseCase;
import io.github.iaarencibia.notifications.application.port.in.release.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The timer that drives the whole thing: registering only one of the two passes, or none, would
 * leave every other test in the suite green while the delivered service either never dispatches or
 * never recovers abandoned work.
 */
class DispatchSchedulerConfigTest {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration REAPER_INTERVAL = Duration.ofMinutes(1);
    private static final Duration QUEUE_REFRESH_INTERVAL = Duration.ofSeconds(5);

    private final CountingDispatcher dispatcher = new CountingDispatcher();
    private final CountingReaper reaper = new CountingReaper();
    private final CountingObserver observer = new CountingObserver();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScheduledTaskRegistrar schedule = configuredRegistrar();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void captureTheLogger() {
        logger = (Logger) LoggerFactory.getLogger(DispatchSchedulerConfig.class);
        logger.setLevel(Level.DEBUG);
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void releaseTheLogger() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("registers all three passes, each at its own configured interval")
    void registersAllPasses() {
        ScheduledTaskRegistrar registrar = configuredRegistrar();

        assertThat(registrar.getFixedDelayTaskList())
                .as("a missing pass is either no dispatching, no recovery or no queue depth")
                .hasSize(3);
        assertThat(
                        registrar.getFixedDelayTaskList().stream()
                                .map(task -> task.getIntervalDuration()))
                .containsExactly(POLL_INTERVAL, REAPER_INTERVAL, QUEUE_REFRESH_INTERVAL);
    }

    @Test
    @DisplayName("the passes it registers are dispatch, reaper and queue refresh, in that order")
    void registersTheRightPasses() {
        // Asserted by running them, because the intervals alone would still match if both tasks
        // called the same use case twice.
        ScheduledTaskRegistrar registrar = configuredRegistrar();

        registrar.getFixedDelayTaskList().getFirst().getRunnable().run();
        assertThat(dispatcher.passes).isEqualTo(1);
        assertThat(reaper.passes).isZero();

        registrar.getFixedDelayTaskList().get(1).getRunnable().run();
        assertThat(reaper.passes).isEqualTo(1);
        assertThat(observer.passes).isZero();

        registrar.getFixedDelayTaskList().get(2).getRunnable().run();
        assertThat(observer.passes).isEqualTo(1);
    }

    @Test
    @DisplayName("a pass that fails does not escape, so the timer survives it")
    void aFailedPassDoesNotEndTheSchedule() {
        // Otherwise the service would keep running and stop delivering, with one stack trace to
        // explain it.
        ScheduledTaskRegistrar registrar =
                registrarFor(
                        () -> {
                            throw new IllegalStateException("the database is unreachable");
                        });

        assertThatCode(() -> registrar.getFixedDelayTaskList().getFirst().getRunnable().run())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a queue depth refresh that fails does not escape, so the timer survives it")
    void aFailedRefreshDoesNotEndTheSchedule() {
        observer.failing = true;

        assertThatCode(
                        () ->
                                configuredRegistrar()
                                        .getFixedDelayTaskList()
                                        .get(2)
                                        .getRunnable()
                                        .run())
                .doesNotThrowAnyException();
        assertThat(observer.passes).isEqualTo(1);
    }

    @Test
    @DisplayName("logs the first failure of a pass at WARN, with the stack trace")
    void logsTheFirstFailureWithItsStackTrace() {
        dispatcher.failing = true;

        runDispatch();

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("the dispatch pass failed and will be retried on the next tick");
        assertThat(event.getThrowableProxy()).isNotNull();
    }

    @Test
    @DisplayName("logs a consecutive failure at DEBUG, without a stack trace")
    void logsAConsecutiveFailureQuietly() {
        dispatcher.failing = true;
        runDispatch();
        captured.list.clear();

        runDispatch();

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage())
                .isEqualTo(
                        "the dispatch pass is still failing:"
                                + " java.lang.IllegalStateException: the database is unreachable");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("logs the first success after failures at INFO, once")
    void logsTheRecoveryOnce() {
        dispatcher.failing = true;
        runDispatch();
        runDispatch();
        captured.list.clear();
        dispatcher.failing = false;

        runDispatch();
        runDispatch();

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo("the dispatch pass recovered");
    }

    @Test
    @DisplayName("warns again when a pass fails after having recovered")
    void warnsAgainAfterARecovery() {
        dispatcher.failing = true;
        runDispatch();
        dispatcher.failing = false;
        runDispatch();
        captured.list.clear();
        dispatcher.failing = true;

        runDispatch();

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("logs nothing extra for a pass that never failed")
    void logsNothingForAHealthyPass() {
        runDispatch();
        runDispatch();

        assertThat(captured.list).isEmpty();
    }

    @Test
    @DisplayName("tracks each pass on its own: one pass failing does not change what another logs")
    void tracksEachPassIndependently() {
        dispatcher.failing = true;
        runDispatch();
        captured.list.clear();
        observer.failing = true;

        runPass(2);

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).startsWith("the queue depth pass failed");
    }

    @Test
    @DisplayName("tracks the reaper separately from the dispatcher")
    void tracksTheReaperSeparately() {
        dispatcher.failing = true;
        runDispatch();
        captured.list.clear();
        reaper.failing = true;

        runPass(1);

        assertThat(captured.list).hasSize(1);
        assertThat(captured.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(captured.list.getFirst().getFormattedMessage()).startsWith("the reaper pass");
    }

    @Test
    @DisplayName("counts the claims the reaper released")
    void countsReleasedClaims() {
        reaper.releasing = 3;

        configuredRegistrar().getFixedDelayTaskList().get(1).getRunnable().run();

        assertThat(registry.get("notifications.claims.released").counter().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("exports released claims at zero before the reaper has run")
    void startsReleasedClaimsAtZero() {
        configuredRegistrar();

        assertThat(registry.get("notifications.claims.released").counter().count()).isZero();
    }

    @Test
    @DisplayName("keeps released claims at zero after a reaper pass that released nothing")
    void countsNothingWhenNothingWasReleased() {
        configuredRegistrar().getFixedDelayTaskList().get(1).getRunnable().run();

        assertThat(registry.get("notifications.claims.released").counter().count()).isZero();
    }

    @Test
    @DisplayName("is registered by default, and only switched off deliberately")
    void isOnUnlessTurnedOff() {
        // The tests turn it off for every context they boot, so nothing else would notice if the
        // condition were inverted and the delivered service shipped without a timer.
        contextRunner()
                .run(context -> assertThat(context).hasSingleBean(DispatchSchedulerConfig.class));

        contextRunner()
                .withPropertyValues("notifications.dispatch.scheduled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DispatchSchedulerConfig.class));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DispatchSchedulerConfig.class)
                .withBean(DispatchDueNotificationsUseCase.class, () -> dispatcher)
                .withBean(ReleaseAbandonedClaimsUseCase.class, () -> reaper)
                .withBean(ObserveQueueDepthUseCase.class, () -> observer)
                .withBean(MeterRegistry.class, () -> registry)
                .withBean(NotificationsProperties.class, DispatchSchedulerConfigTest::properties);
    }

    private void runDispatch() {
        runPass(0);
    }

    private void runPass(int index) {
        schedule.getFixedDelayTaskList().get(index).getRunnable().run();
    }

    private ScheduledTaskRegistrar configuredRegistrar() {
        return registrarFor(dispatcher::dispatchDue);
    }

    private ScheduledTaskRegistrar registrarFor(Runnable pass) {
        DispatchSchedulerConfig config =
                new DispatchSchedulerConfig(
                        () -> {
                            pass.run();
                            return 0;
                        },
                        reaper,
                        observer,
                        properties(),
                        registry);

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        config.configureTasks(registrar);
        return registrar;
    }

    /**
     * @return a complete configuration whose three intervals differ, so a task registered with the
     *     wrong one is visible
     */
    private static NotificationsProperties properties() {
        return new NotificationsProperties(
                "notification-service-1",
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Dispatch(POLL_INTERVAL, 25, 8, 100),
                new NotificationsProperties.Reaper(Duration.ofMinutes(5), REAPER_INTERVAL),
                new NotificationsProperties.QueueMetrics(QUEUE_REFRESH_INTERVAL),
                new NotificationsProperties.Retry(
                        3, Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(
                                Duration.ofSeconds(2), Duration.ofSeconds(5), List.of())));
    }

    private static final class CountingDispatcher implements DispatchDueNotificationsUseCase {

        private int passes;
        private boolean failing;

        @Override
        public int dispatchDue() {
            passes++;
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
            return 0;
        }
    }

    private static final class CountingReaper implements ReleaseAbandonedClaimsUseCase {

        private int passes;
        private int releasing;
        private boolean failing;

        @Override
        public int releaseAbandonedClaims() {
            passes++;
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
            return releasing;
        }
    }

    private static final class CountingObserver implements ObserveQueueDepthUseCase {

        private int passes;
        private boolean failing;

        @Override
        public void observeQueueDepth() {
            passes++;
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
        }
    }
}
