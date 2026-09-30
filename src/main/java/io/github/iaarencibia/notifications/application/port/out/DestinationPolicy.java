package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Channel;
import java.util.Optional;

/** The reason this deployment will not deliver to a recipient, when it will not. */
public interface DestinationPolicy {

    Optional<String> refuse(Channel channel, String recipient);
}
