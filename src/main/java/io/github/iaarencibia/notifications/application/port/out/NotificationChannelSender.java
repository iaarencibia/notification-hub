package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;

/**
 * One way of delivering a notification, said in the language of the domain: nothing above this port
 * knows what a status code or an SMTP session is.
 *
 * <p>Implementations must not throw for a failed delivery. A refused or unanswering destination is
 * an outcome, not an error.
 */
public interface NotificationChannelSender {

    Channel channel();

    /**
     * Never retries on its own.
     *
     * @return delivered, worth retrying, or rejected
     */
    DispatchOutcome send(Notification notification);
}
