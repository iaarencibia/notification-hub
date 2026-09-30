package io.github.iaarencibia.notifications.adapter.out.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.domain.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceDestinationPolicyTest {

    private static final String FORM = "must be an absolute http or https URL without credentials";

    private final ServiceDestinationPolicy policy = new ServiceDestinationPolicy();

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
                "not a url"
            })
    @DisplayName("refuses a recipient that is not an http or https URL without credentials")
    void refusesTheWrongForm(String recipient) {
        assertThat(policy.refuse(Channel.SERVICE, recipient)).contains(FORM);
    }

    @ParameterizedTest
    @EnumSource(
            value = Channel.class,
            names = {"LOG", "EMAIL"})
    @DisplayName("has no opinion on the recipient of any other channel")
    void ignoresOtherChannels(Channel channel) {
        assertThat(policy.refuse(channel, "ops-team")).isEmpty();
    }
}
