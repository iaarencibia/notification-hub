package io.github.iaarencibia.notifications.adapter.out.observability;

import io.github.iaarencibia.notifications.application.port.out.ClaimLostException;
import io.github.iaarencibia.notifications.application.port.out.DispatchEvents;
import io.github.iaarencibia.notifications.domain.Notification;
import io.github.iaarencibia.notifications.domain.NotificationAttempt;
import java.util.List;

/** Reports each dispatch event to every listener, in order. */
public final class CompositeDispatchEvents implements DispatchEvents {

    private final List<DispatchEvents> listeners;

    public CompositeDispatchEvents(List<DispatchEvents> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void claimed(Notification notification) {
        listeners.forEach(listener -> listener.claimed(notification));
    }

    @Override
    public void dispatched(Notification notification, NotificationAttempt attempt) {
        listeners.forEach(listener -> listener.dispatched(notification, attempt));
    }

    @Override
    public void claimLost(Notification notification, ClaimLostException cause) {
        listeners.forEach(listener -> listener.claimLost(notification, cause));
    }

    @Override
    public void failed(Notification notification, RuntimeException cause) {
        listeners.forEach(listener -> listener.failed(notification, cause));
    }
}
