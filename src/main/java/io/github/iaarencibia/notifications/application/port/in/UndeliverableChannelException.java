package io.github.iaarencibia.notifications.application.port.in;

import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.domain.Channel;

/**
 * Thrown when a caller asks for a channel this deployment cannot deliver. Refused at intake so a
 * request is never accepted only to fail on every retry.
 */
public class UndeliverableChannelException extends RuntimeException {

    private final Channel channel;
    private final DeliverableChannels deliverable;

    public UndeliverableChannelException(Channel channel, DeliverableChannels deliverable) {
        super("channel " + channel + " cannot be delivered by this service");
        this.channel = channel;
        this.deliverable = deliverable;
    }

    public Channel channel() {
        return channel;
    }

    public DeliverableChannels deliverable() {
        return deliverable;
    }
}
