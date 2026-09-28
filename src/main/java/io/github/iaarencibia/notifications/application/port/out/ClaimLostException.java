package io.github.iaarencibia.notifications.application.port.out;

import java.util.UUID;

/**
 * Thrown when the result of a delivery arrives for a notification that moved on since it was
 * claimed: the reaper judged the claim abandoned and released it. Expected, not a defect; the
 * caller has nothing to do but discard the result.
 */
public class ClaimLostException extends RuntimeException {

    private final UUID notificationId;

    public ClaimLostException(UUID notificationId) {
        super(
                "the claim on notification "
                        + notificationId
                        + " was lost before its result "
                        + "could be recorded");
        this.notificationId = notificationId;
    }

    public UUID notificationId() {
        return notificationId;
    }
}
