package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Assembles the channels that need something built for them.
 *
 * <p>Lives beside the adapters, not in the configuration package: what it wires is an adapter's own
 * machinery -- a client with its timeouts -- and nothing above this package may know the SERVICE
 * channel speaks HTTP at all.
 */
@Configuration(proxyBeanMethods = false)
class ChannelConfig {

    /**
     * Names the outbound client at both ends, so that neither the bean method's name nor an
     * injection point's parameter name is what ties them together.
     */
    private static final String SERVICE_CHANNEL_CLIENT = "serviceChannelRestClient";

    /**
     * A client of its own, not a shared one: its timeouts are chosen for a destination this service
     * does not control, which is not a posture any other outbound call would want. Not a default
     * candidate either -- a later call asking for a {@code RestClient} by type would otherwise
     * silently inherit a timeout nobody chose for it.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(SERVICE_CHANNEL_CLIENT)
    RestClient serviceChannelRestClient(
            RestClient.Builder builder, NotificationsProperties properties) {
        // Boot's builder, not RestClient.builder(): the static factory skips every customizer,
        // including the one that instruments the call -- and this is the only call whose latency
        // the resilience requirement is about.
        return builder.requestFactory(requestFactoryFor(properties.channels().service())).build();
    }

    /**
     * Shared with the channel's own test, so the test measures the client this service actually
     * posts with -- the classification under test is built entirely on how a timeout arrives.
     *
     * <p>The read timeout matters most: the connect timeout only covers a destination that never
     * accepts the connection, and one that accepts and then goes quiet -- the more common and more
     * expensive failure -- is caught only by the read timeout.
     */
    static ClientHttpRequestFactory requestFactoryFor(
            NotificationsProperties.Channels.Service service) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(service.connectTimeout());
        requestFactory.setReadTimeout(service.readTimeout());
        return requestFactory;
    }

    /**
     * @param serviceChannelRestClient the client built above, asked for by name since it is not
     *     offered to anyone who does not
     */
    @Bean
    NotificationChannelSender httpServiceChannelSender(
            @Qualifier(SERVICE_CHANNEL_CLIENT) RestClient serviceChannelRestClient) {
        return new HttpServiceChannelSender(serviceChannelRestClient);
    }
}
