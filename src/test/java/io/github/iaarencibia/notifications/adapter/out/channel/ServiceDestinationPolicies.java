package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.config.NotificationsProperties;
import java.time.Duration;
import java.util.List;

final class ServiceDestinationPolicies {

    private ServiceDestinationPolicies() {}

    static ServiceDestinationPolicy allowing(String... hosts) {
        return new ServiceDestinationPolicy(
                new NotificationsProperties(
                        "test-instance",
                        new NotificationsProperties.Security("a-key"),
                        new NotificationsProperties.Dispatch(Duration.ofSeconds(1), 25, 8, 100),
                        new NotificationsProperties.Reaper(
                                Duration.ofMinutes(5), Duration.ofMinutes(1)),
                        new NotificationsProperties.Retry(
                                3, Duration.ofSeconds(5), 3.0, Duration.ofMinutes(5), 0.2),
                        new NotificationsProperties.Channels(
                                new NotificationsProperties.Channels.Service(
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(5),
                                        List.of(hosts)))));
    }
}
