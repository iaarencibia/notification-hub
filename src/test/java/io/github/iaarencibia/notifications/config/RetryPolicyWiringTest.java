package io.github.iaarencibia.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.iaarencibia.notifications.domain.RetryPolicy;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the configured schedule is the schedule the policy applies.
 *
 * <p>The hazard is the shape of the constructor: four arguments in two pairs of the same type, so
 * crossing the two durations compiles, and so does crossing the two doubles. Nothing about the
 * running system would look wrong afterwards -- retries would simply be spaced by numbers nobody
 * chose.
 */
class RetryPolicyWiringTest {

    private final UseCaseConfig config = new UseCaseConfig();

    @Test
    @DisplayName("gives each configured value to the argument it belongs to")
    void wiresEachValueToItsOwnArgument() {
        // Every value distinct, none a multiple of another, so a crossed pair changes the result
        // instead of cancelling out. Jitter is off so the three delays are exact.
        RetryPolicy policy =
                config.retryPolicy(
                        propertiesWith(Duration.ofSeconds(4), 5.0, Duration.ofMinutes(7), 0.0));

        assertThat(policy.delayFor(1, null))
                .as("the first delay is the configured initial backoff")
                .isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayFor(2, null))
                .as("the second is the first grown by the configured multiplier")
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayFor(9, null))
                .as("a delay that outgrows the configured ceiling is capped at it")
                .isEqualTo(Duration.ofMinutes(7));
    }

    @Test
    @DisplayName("passes the configured jitter through, rather than a constant of its own")
    void wiresTheJitter() {
        // With jitter wired, two delays for the same attempt differ; a hardcoded zero would make
        // every one identical, and the assertions above would not notice since they run with
        // jitter off.
        RetryPolicy policy =
                config.retryPolicy(
                        propertiesWith(Duration.ofSeconds(4), 2.0, Duration.ofMinutes(7), 1.0));

        Set<Duration> delays = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            delays.add(policy.delayFor(1, null));
        }

        assertThat(delays).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("refuses to start on a schedule whose delays would shrink")
    void refusesAScheduleThatShrinks() {
        // The policy owns this rule, not bean validation; building it here is what makes the
        // refusal happen at startup rather than at the first failed delivery.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                config.retryPolicy(
                                        propertiesWith(
                                                Duration.ofSeconds(4),
                                                0.5,
                                                Duration.ofMinutes(7),
                                                0.0)))
                .withMessageContaining("multiplier");
    }

    private static NotificationsProperties propertiesWith(
            Duration initialBackoff, double multiplier, Duration maxBackoff, double jitter) {
        return new NotificationsProperties(
                "test-instance",
                new NotificationsProperties.Security("a-key"),
                new NotificationsProperties.Dispatch(Duration.ofSeconds(1), 25, 8, 100),
                new NotificationsProperties.Reaper(Duration.ofMinutes(5), Duration.ofMinutes(1)),
                new NotificationsProperties.QueueMetrics(Duration.ofSeconds(5)),
                new NotificationsProperties.Retry(
                        3, initialBackoff, multiplier, maxBackoff, jitter),
                new NotificationsProperties.Channels(
                        new NotificationsProperties.Channels.Service(
                                Duration.ofSeconds(2), Duration.ofSeconds(5), List.of())));
    }
}
