package io.github.iaarencibia.notifications.application.port.in.submit;

import java.util.UUID;

/**
 * Thrown when an idempotency key is repeated with a payload other than the one it was filed under.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;
    private final UUID existingId;

    public IdempotencyKeyReusedException(String idempotencyKey, UUID existingId) {
        super(
                "idempotency key "
                        + idempotencyKey
                        + " was already used for notification "
                        + existingId);
        this.idempotencyKey = idempotencyKey;
        this.existingId = existingId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public UUID existingId() {
        return existingId;
    }
}
