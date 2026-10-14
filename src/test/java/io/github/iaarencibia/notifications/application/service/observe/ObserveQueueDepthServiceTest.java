package io.github.iaarencibia.notifications.application.service.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.iaarencibia.notifications.application.port.out.QueueDepth;
import io.github.iaarencibia.notifications.application.port.out.QueueDepthEvents;
import io.github.iaarencibia.notifications.application.port.out.QueueInspection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the queue depth refresh tells the metrics, whether or not the database answered. */
class ObserveQueueDepthServiceTest {

    private final RecordingEvents events = new RecordingEvents();

    @Test
    @DisplayName("reports the depth it measured")
    void reportsTheMeasuredDepth() {
        QueueDepth depth = new QueueDepth(4, 2);

        new ObserveQueueDepthService(() -> depth, events).observeQueueDepth();

        assertThat(events.measured).containsExactly(depth);
        assertThat(events.failures).isZero();
    }

    @Test
    @DisplayName("reports the loss of the measurement and lets the failure through")
    void reportsAFailedMeasurementAndRethrows() {
        QueueInspection unreachable =
                () -> {
                    throw new IllegalStateException("the database is unreachable");
                };

        assertThatThrownBy(
                        () -> new ObserveQueueDepthService(unreachable, events).observeQueueDepth())
                .isInstanceOf(IllegalStateException.class);

        assertThat(events.failures).isEqualTo(1);
        assertThat(events.measured).isEmpty();
    }

    @Test
    @DisplayName("refuses to be built without its collaborators")
    void refusesMissingCollaborators() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ObserveQueueDepthService(null, events));
        assertThatNullPointerException()
                .isThrownBy(() -> new ObserveQueueDepthService(() -> new QueueDepth(0, 0), null));
    }

    private static final class RecordingEvents implements QueueDepthEvents {

        private final List<QueueDepth> measured = new ArrayList<>();
        private int failures;

        @Override
        public void measured(QueueDepth depth) {
            measured.add(depth);
        }

        @Override
        public void measurementFailed() {
            failures++;
        }
    }
}
