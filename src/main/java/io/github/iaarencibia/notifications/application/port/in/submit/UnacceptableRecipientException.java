package io.github.iaarencibia.notifications.application.port.in.submit;

/** Thrown when the recipient is not one this deployment will deliver to. */
public class UnacceptableRecipientException extends RuntimeException {

    private final String reason;

    public UnacceptableRecipientException(String reason) {
        super("recipient " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
