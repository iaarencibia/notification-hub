package io.github.iaarencibia.notifications.application;

import io.github.iaarencibia.notifications.domain.Channel;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The channels this deployment can actually send.
 *
 * @param value the channels with an adapter behind them
 */
public record DeliverableChannels(Set<Channel> value) {

    public DeliverableChannels {
        // EnumSet.copyOf refuses an empty collection that is not already one, so the empty case
        // is handled before it.
        value = value.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(value));
    }

    /** Whether anything in this deployment can send the given channel. */
    public boolean includes(Channel channel) {
        return value.contains(channel);
    }

    /**
     * Never empty in a running deployment: an adapter is a required dependency.
     *
     * @return the channels in a stable order, so identical refusals read identically
     */
    public String describe() {
        return value.stream().map(Channel::name).collect(Collectors.joining(", "));
    }
}
