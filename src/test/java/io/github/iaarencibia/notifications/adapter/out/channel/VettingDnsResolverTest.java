package io.github.iaarencibia.notifications.adapter.out.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VettingDnsResolverTest {

    @Test
    @DisplayName("hands back every address of a name that resolves only to public ones")
    void passesPublicAddresses() throws Exception {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing(),
                        StubDnsResolver.answering("hook.test", "8.8.8.8", "2001:4860:4860::8888"));

        assertThat(resolver.resolve("hook.test"))
                .containsExactly(
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("2001:4860:4860::8888"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5", "127.0.0.1", "169.254.169.254", "::1", "fd00::1"})
    @DisplayName("refuses a name that resolves to a private or local address")
    void refusesABlockedAddress(String address) throws Exception {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing(),
                        StubDnsResolver.answering("hook.test", address));

        assertThatExceptionOfType(VettingDnsResolver.RefusedAddressException.class)
                .isThrownBy(() -> resolver.resolve("hook.test"))
                .withMessage(
                        "recipient resolves to a private or local address ("
                                + InetAddress.getByName(address).getHostAddress()
                                + ")");
    }

    @Test
    @DisplayName("refuses the whole answer when one address among public ones is blocked")
    void refusesWhenAnyAddressIsBlocked() {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing(),
                        StubDnsResolver.answering("hook.test", "8.8.8.8", "10.0.0.5", "1.1.1.1"));

        assertThatExceptionOfType(VettingDnsResolver.RefusedAddressException.class)
                .isThrownBy(() -> resolver.resolve("hook.test"))
                .withMessageEndingWith("(10.0.0.5)");
    }

    @Test
    @DisplayName("lets an allow-listed host through even when it resolves to a local address")
    void passesAnAllowedHostUntouched() throws Exception {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing("Hook.Test."),
                        StubDnsResolver.answering("hook.test", "10.0.0.5"));

        assertThat(resolver.resolve("hook.test"))
                .containsExactly(InetAddress.getByName("10.0.0.5"));
    }

    @Test
    @DisplayName("lets an allow-listed address literal through")
    void passesAnAllowedLiteral() throws Exception {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing("::1"),
                        StubDnsResolver.answering("::1", "::1"));

        assertThat(resolver.resolve("::1")).containsExactly(InetAddress.getByName("::1"));
    }

    @Test
    @DisplayName("lets a name that does not resolve fail as the delegate reports it")
    void propagatesAnUnknownHost() {
        VettingDnsResolver resolver =
                new VettingDnsResolver(
                        ServiceDestinationPolicies.allowing(), StubDnsResolver.knowingNothing());

        assertThatThrownBy(() -> resolver.resolve("no-such-host.test"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(VettingDnsResolver.RefusedAddressException.class)
                .hasMessage("no-such-host.test");
    }

    @Test
    @DisplayName("resolves once per lookup, so that the answer vetted is the answer returned")
    void asksTheDelegateOnce() throws Exception {
        StubDnsResolver delegate = StubDnsResolver.answering("hook.test", "8.8.8.8");
        VettingDnsResolver resolver =
                new VettingDnsResolver(ServiceDestinationPolicies.allowing(), delegate);

        resolver.resolve("hook.test");

        assertThat(delegate.lookups()).containsExactly("hook.test");
    }
}
