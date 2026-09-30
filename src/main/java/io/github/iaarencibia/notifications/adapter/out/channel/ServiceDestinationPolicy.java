package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.DestinationPolicy;
import io.github.iaarencibia.notifications.domain.Channel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Refuses SERVICE recipients that are not HTTP URLs. */
@Component
final class ServiceDestinationPolicy implements DestinationPolicy {

    private static final String FORM = "must be an absolute http or https URL without credentials";

    @Override
    public Optional<String> refuse(Channel channel, String recipient) {
        if (channel != Channel.SERVICE) {
            return Optional.empty();
        }
        URI destination = parse(recipient);
        if (destination == null || whyUnusable(destination).isPresent()) {
            return Optional.of(FORM);
        }
        return Optional.empty();
    }

    private static Optional<String> whyUnusable(URI destination) {
        if (!destination.isAbsolute()) {
            return Optional.of("recipient must be an absolute URL, and this one names no scheme");
        }
        String scheme = destination.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return Optional.of("recipient must use http or https, and this one uses " + scheme);
        }
        if (destination.getHost() == null || destination.getHost().isBlank()) {
            return Optional.of("recipient names no host");
        }
        // -1 is "no port given", which is legal and means the scheme's default.
        if (destination.getPort() != -1
                && (destination.getPort() < 1 || destination.getPort() > 65535)) {
            return Optional.of(
                    "recipient names a port outside the legal range: " + destination.getPort());
        }
        if (destination.getUserInfo() != null) {
            return Optional.of("recipient must not carry credentials");
        }
        return Optional.empty();
    }

    private static URI parse(String recipient) {
        try {
            return new URI(recipient);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
