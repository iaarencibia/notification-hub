package io.github.iaarencibia.notifications.adapter.out.channel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Resolves a destination host for the connection itself, refusing any that lands somewhere local.
 */
final class VettingDnsResolver implements DnsResolver {

    private final ServiceDestinationPolicy policy;
    private final DnsResolver delegate;

    VettingDnsResolver(ServiceDestinationPolicy policy, DnsResolver delegate) {
        this.policy = policy;
        this.delegate = delegate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (policy.allows(host)) {
            return addresses;
        }
        for (InetAddress address : addresses) {
            if (ServiceDestinationPolicy.isBlocked(address)) {
                throw new RefusedAddressException(address);
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }

    static final class RefusedAddressException extends UnknownHostException {

        RefusedAddressException(InetAddress address) {
            super(
                    "recipient resolves to a private or local address ("
                            + address.getHostAddress()
                            + ")");
        }
    }
}
