package io.github.iaarencibia.notifications.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.application.port.out.QueueDepth;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the queue gauges say before the first reading, after one, and after one is lost. */
class MetricsQueueDepthTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsQueueDepth gauges = new MetricsQueueDepth(registry);

    @Test
    @DisplayName("reports NaN for both statuses until the first reading")
    void reportsNaNBeforeTheFirstReading() {
        assertThat(rows("PENDING")).isNaN();
        assertThat(rows("DISPATCHING")).isNaN();
    }

    @Test
    @DisplayName("reports each status under its own tag")
    void reportsTheMeasuredDepth() {
        gauges.measured(new QueueDepth(7, 3));

        assertThat(rows("PENDING")).isEqualTo(7);
        assertThat(rows("DISPATCHING")).isEqualTo(3);
    }

    @Test
    @DisplayName("reports zero, not a missing series, for a status with no rows")
    void reportsZeroForAnEmptyStatus() {
        gauges.measured(new QueueDepth(0, 5));

        assertThat(rows("PENDING")).isZero();
        assertThat(rows("DISPATCHING")).isEqualTo(5);
    }

    @Test
    @DisplayName("reports NaN once a reading is lost, rather than the last value it had")
    void reportsNaNAfterAFailedReading() {
        gauges.measured(new QueueDepth(7, 3));

        gauges.measurementFailed();

        assertThat(rows("PENDING")).isNaN();
        assertThat(rows("DISPATCHING")).isNaN();
    }

    @Test
    @DisplayName("recovers on the next successful reading")
    void recoversAfterAFailedReading() {
        gauges.measured(new QueueDepth(7, 3));
        gauges.measurementFailed();

        gauges.measured(new QueueDepth(1, 0));

        assertThat(rows("PENDING")).isEqualTo(1);
        assertThat(rows("DISPATCHING")).isZero();
    }

    private double rows(String status) {
        return registry.get("notifications.queue.rows").tag("status", status).gauge().value();
    }
}
