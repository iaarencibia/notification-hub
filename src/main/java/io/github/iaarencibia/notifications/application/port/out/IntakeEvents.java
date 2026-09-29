package io.github.iaarencibia.notifications.application.port.out;

import io.github.iaarencibia.notifications.domain.Notification;

/** What happened at intake, reported in the language of the domain. */
public interface IntakeEvents {

    void accepted(Notification notification);
}
