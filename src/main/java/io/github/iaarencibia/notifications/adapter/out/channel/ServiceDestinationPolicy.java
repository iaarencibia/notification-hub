package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.DestinationPolicy;
import io.github.iaarencibia.notifications.config.NotificationsProperties;
import io.github.iaarencibia.notifications.domain.Channel;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Refuses SERVICE recipients that are not HTTP URLs, or that point at a private or local host. */
@Component
final class ServiceDestinationPolicy implements DestinationPolicy {

    private static final String FORM = "must be an absolute http or https URL without credentials";

    private final Set<String> allowedHosts;

    ServiceDestinationPolicy(NotificationsProperties properties) {
        this.allowedHosts =
                properties.channels().service().allowedHosts().stream()
                        .map(String::strip)
                        .filter(entry -> !entry.isEmpty())
                        .map(ServiceDestinationPolicy::bare)
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<String> refuse(Channel channel, String recipient) {
        if (channel != Channel.SERVICE) {
            return Optional.empty();
        }
        URI destination = parse(recipient);
        if (destination == null || whyUnusable(destination).isPresent()) {
            return Optional.of(FORM);
        }
        String host = hostOf(destination);
        if (isAllowed(host)) {
            return Optional.empty();
        }
        if (isLocalName(host)) {
            return Optional.of("must not name a local address");
        }
        // No DNS here: that is I/O on the request thread, and the answer can change by dispatch.
        if (isBlockedLiteral(host)) {
            return Optional.of("must not name a private or local address");
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

    private boolean isAllowed(String host) {
        return allowedHosts.contains(host);
    }

    private static URI parse(String recipient) {
        try {
            return new URI(recipient);
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static String hostOf(URI destination) {
        return bare(destination.getHost());
    }

    private static String bare(String host) {
        String lowerCase = unbracketed(host).toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".") ? lowerCase.substring(0, lowerCase.length() - 1) : lowerCase;
    }

    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static boolean isLocalName(String host) {
        return host.equals("localhost") || host.endsWith(".localhost");
    }

    private static boolean isBlockedLiteral(String host) {
        if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) {
            return false;
        }
        try {
            return isBlocked(InetAddress.getByName(host));
        } catch (UnknownHostException notALiteral) {
            return false;
        }
    }

    private static boolean isBlocked(InetAddress address) {
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            return isBlockedIpv4(octets);
        }
        boolean uniqueLocal = (octets[0] & 0xFE) == 0xFC;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || uniqueLocal;
    }

    private static boolean isBlockedIpv4(byte[] octets) {
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        int third = octets[2] & 0xFF;
        return first == 0
                || first == 10
                || first == 127
                || first >= 224
                || (first == 100 && (second & 0xC0) == 0x40)
                || (first == 169 && second == 254)
                || (first == 172 && (second & 0xF0) == 16)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 198 && (second & 0xFE) == 18);
    }
}
