package io.github.iaarencibia.notifications.adapter.out.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.iaarencibia.notifications.domain.Channel;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceDestinationPolicyTest {

    private static final String FORM = "must be an absolute http or https URL without credentials";

    private final ServiceDestinationPolicy policy = ServiceDestinationPolicies.allowing();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ftp://destination.test/hook",
                "file:///etc/passwd",
                "destination.test/hook",
                "/hook",
                "http://",
                "http:///hook",
                "http://user:pw@destination.test/hook",
                "http://user@destination.test/hook",
                "http://destination.test:70000/hook",
                "http://destination.test:0/hook",
                "http://destination.test:-5/hook",
                "http://127.1/hook",
                "http://0x7f.1/hook",
                "http://10.0.0.5./hook",
                "not a url"
            })
    @DisplayName("refuses a recipient that is not an http or https URL without credentials")
    void refusesTheWrongForm(String recipient) {
        assertThat(policy.refuse(Channel.SERVICE, recipient)).contains(FORM);
    }

    @Test
    @DisplayName("checks the form before the allow list, which never exempts a scheme")
    void anAllowedHostDoesNotExemptTheForm() {
        ServiceDestinationPolicy allowing = ServiceDestinationPolicies.allowing("destination.test");

        assertThat(allowing.refuse(Channel.SERVICE, "ftp://destination.test/x")).contains(FORM);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1/hook",
                "http://10.0.0.5/hook",
                "http://2130706433/hook",
                "http://172.16.0.1/hook",
                "http://192.168.1.1/hook",
                "http://169.254.169.254/latest/meta-data",
                "http://0.0.0.0/hook",
                "http://0.1.2.3/hook",
                "http://100.64.0.1/hook",
                "http://192.0.0.8/hook",
                "http://198.18.0.1/hook",
                "http://198.19.255.255/hook",
                "http://224.0.0.1/hook",
                "http://240.0.0.1/hook",
                "http://255.255.255.255/hook",
                "http://[::1]/hook",
                "http://[fd00::1]/hook",
                "http://[fe80::1]/hook",
                "http://[::ffff:127.0.0.1]/hook",
                "http://[64:ff9b::a9fe:a9fe]/hook",
                "http://[64:ff9b::7f00:1]/hook",
                "http://[2002:a9fe:a9fe::1]/hook",
                "http://[2002:0a00:0001::]/hook",
                "http://[::a9fe:a9fe]/hook",
                "http://10.0.0.5:8080/hook",
                "HTTP://10.0.0.5/hook"
            })
    @DisplayName("refuses an address literal in a private or local range")
    void refusesPrivateLiterals(String recipient) {
        assertThat(policy.refuse(Channel.SERVICE, recipient))
                .contains("must not name a private or local address");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/hook",
                "http://LOCALHOST:8080/hook",
                "http://api.localhost/hook",
                "http://a.b.Localhost/hook",
                "http://localhost./hook",
                "http://api.localhost./hook"
            })
    @DisplayName("refuses localhost and its subdomains by name")
    void refusesLocalhostNames(String recipient) {
        assertThat(policy.refuse(Channel.SERVICE, recipient))
                .contains("must not name a local address");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://8.8.8.8/hook",
                "https://100.63.255.255/hook",
                "https://100.128.0.1/hook",
                "https://192.0.1.1/hook",
                "https://198.17.255.255/hook",
                "https://198.20.0.1/hook",
                "https://example.com/hook",
                "https://destination.test/hook",
                "https://notlocalhost/hook",
                "https://localhost.example.com/hook",
                "http://[2001:4860:4860::8888]/hook",
                "http://[64:ff9b::808:808]/hook",
                "http://[2002:808:808::1]/hook",
                "http://[::808:808]/hook"
            })
    @DisplayName("accepts a public address literal or a name, without resolving it")
    void acceptsPublicDestinations(String recipient) {
        assertThat(policy.refuse(Channel.SERVICE, recipient)).isEmpty();
    }

    @Test
    @DisplayName("accepts an allow-listed host that would otherwise be refused, in any case")
    void acceptsAnAllowedHost() {
        ServiceDestinationPolicy allowing =
                ServiceDestinationPolicies.allowing("LocalHost", "10.0.0.5", "::1");

        assertThat(allowing.refuse(Channel.SERVICE, "http://localhost:8080/hook")).isEmpty();
        assertThat(allowing.refuse(Channel.SERVICE, "http://10.0.0.5/hook")).isEmpty();
        assertThat(allowing.refuse(Channel.SERVICE, "http://[::1]/hook")).isEmpty();
        assertThat(allowing.refuse(Channel.SERVICE, "http://10.0.0.6/hook")).isPresent();
    }

    @Test
    @DisplayName("matches an allow-listed host with or without a trailing dot")
    void allowListIgnoresATrailingDot() {
        ServiceDestinationPolicy bare = ServiceDestinationPolicies.allowing("localhost");
        ServiceDestinationPolicy dotted = ServiceDestinationPolicies.allowing("localhost.");

        assertThat(bare.refuse(Channel.SERVICE, "http://localhost./hook")).isEmpty();
        assertThat(dotted.refuse(Channel.SERVICE, "http://localhost/hook")).isEmpty();
        assertThat(dotted.refuse(Channel.SERVICE, "http://localhost./hook")).isEmpty();
    }

    @Test
    @DisplayName("matches an allow-listed IPv6 literal by address, not by spelling")
    void allowListComparesIpv6ByAddress() {
        ServiceDestinationPolicy compressed = ServiceDestinationPolicies.allowing("::1");
        ServiceDestinationPolicy expanded = ServiceDestinationPolicies.allowing("0:0:0:0:0:0:0:1");
        ServiceDestinationPolicy bracketed = ServiceDestinationPolicies.allowing("[::1]");

        assertThat(compressed.refuse(Channel.SERVICE, "http://[0:0:0:0:0:0:0:1]/hook")).isEmpty();
        assertThat(expanded.refuse(Channel.SERVICE, "http://[::1]/hook")).isEmpty();
        assertThat(bracketed.refuse(Channel.SERVICE, "http://[::1]/hook")).isEmpty();
        assertThat(compressed.refuse(Channel.SERVICE, "http://[::2]/hook")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "wiremock:8080",
                "http://wiremock",
                "wiremock/hook",
                "wire mock",
                "user@wiremock",
                "wiremock?x",
                "[::1",
                "::zz",
                "under_score",
                "-wiremock",
                "."
            })
    @DisplayName("refuses at construction an allowed host that is not a name or an address")
    void refusesAMalformedAllowedHost(String entry) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ServiceDestinationPolicies.allowing("valid.test", entry))
                .withMessageContaining("'" + entry + "'");
    }

    @Test
    @DisplayName("tolerates blank allowed hosts and whitespace around a valid one")
    void toleratesBlankAndPaddedAllowedHosts() {
        ServiceDestinationPolicy allowing =
                ServiceDestinationPolicies.allowing(" ", "", " localhost ");

        assertThat(allowing.refuse(Channel.SERVICE, "http://localhost/hook")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Channel.class,
            names = {"LOG", "EMAIL"})
    @DisplayName("has no opinion on the recipient of any other channel")
    void ignoresOtherChannels(Channel channel) {
        assertThat(policy.refuse(channel, "ops-team")).isEmpty();
        assertThat(policy.refuse(channel, "http://127.0.0.1/hook")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1/hook",
                "http://2130706433/hook",
                "http://[::1]/hook",
                "http://[64:ff9b::7f00:1]/hook",
                "http://localhost:8080/hook",
                "http://api.localhost./hook"
            })
    @DisplayName(
            "refuses before connecting a local literal or name, which no resolution can change")
    void refusesLocalDestinationsBeforeConnecting(String recipient) {
        assertThat(policy.refuseBeforeConnecting(URI.create(recipient)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("local address"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.1/hook", "http://0x7f.1/hook"})
    @DisplayName("refuses before connecting a shorthand loopback because URI reads it as no host")
    void refusesShorthandLoopbackAsHostless(String recipient) {
        URI destination = URI.create(recipient);

        assertThat(destination.getHost()).isNull();
        assertThat(policy.refuseBeforeConnecting(destination)).contains("recipient names no host");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "example.com/hook",
                "ftp://destination.test/hook",
                "http://user:pw@destination.test/hook",
                "http://destination.test:70000/hook"
            })
    @DisplayName("refuses before connecting what is not an http URL to a host")
    void refusesTheWrongFormBeforeConnecting(String recipient) {
        assertThat(policy.refuseBeforeConnecting(URI.create(recipient))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://8.8.8.8/hook",
                "http://[2001:4860:4860::8888]/hook",
                "https://example.com/hook",
                "http://no-such-host.invalid/hook"
            })
    @DisplayName("leaves a public literal or a name to the resolution that connecting performs")
    void leavesPublicDestinationsToTheResolver(String recipient) {
        assertThat(policy.refuseBeforeConnecting(URI.create(recipient))).isEmpty();
    }

    @Test
    @DisplayName("does not refuse before connecting an allow-listed local literal or name")
    void doesNotRefuseAnAllowedLocalDestinationBeforeConnecting() {
        ServiceDestinationPolicy allowing =
                ServiceDestinationPolicies.allowing("localhost", "10.0.0.5");

        assertThat(allowing.refuseBeforeConnecting(URI.create("http://localhost:8080/hook")))
                .isEmpty();
        assertThat(allowing.refuseBeforeConnecting(URI.create("http://10.0.0.5/hook"))).isEmpty();
    }

    @Test
    @DisplayName("allows a host by the same normalisation the allow list was built with")
    void allowsAListedHostInAnySpelling() {
        ServiceDestinationPolicy allowing =
                ServiceDestinationPolicies.allowing("Hook.Test.", "0:0:0:0:0:0:0:1");

        assertThat(allowing.allows("hook.test")).isTrue();
        assertThat(allowing.allows("HOOK.TEST.")).isTrue();
        assertThat(allowing.allows("::1")).isTrue();
        assertThat(allowing.allows("[::1]")).isTrue();
        assertThat(allowing.allows("other.test")).isFalse();
    }
}
