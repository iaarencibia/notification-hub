package io.github.iaarencibia.notifications.application.port.in;

/** One pass of the dispatcher: claims whatever is due and delivers it. */
public interface DispatchDueNotificationsUseCase {

    /**
     * Returns once the batch is claimed and handed off, not when delivery finishes, so a slow
     * destination does not block the next poll.
     *
     * @return how many notifications this pass claimed, zero when nothing was due
     */
    int dispatchDue();
}
