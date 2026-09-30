package io.github.iaarencibia.notifications.adapter.out.channel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.DnsResolver;

final class StubDnsResolver implements DnsResolver {

    private final Map<String, String[]> answers;
    private final List<String> lookups = new ArrayList<>();

    private StubDnsResolver(Map<String, String[]> answers) {
        this.answers = answers;
    }

    static StubDnsResolver answering(String host, String... addresses) {
        return new StubDnsResolver(Map.of(host, addresses));
    }

    static StubDnsResolver knowingNothing() {
        return new StubDnsResolver(Map.of());
    }

    List<String> lookups() {
        return lookups;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        lookups.add(host);
        String[] addresses = answers.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        InetAddress[] resolved = new InetAddress[addresses.length];
        for (int index = 0; index < addresses.length; index++) {
            resolved[index] = InetAddress.getByName(addresses[index]);
        }
        return resolved;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
