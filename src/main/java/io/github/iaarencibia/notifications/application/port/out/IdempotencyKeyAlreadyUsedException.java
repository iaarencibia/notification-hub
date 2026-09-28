package io.github.iaarencibia.notifications.application.port.out;

/**
 * Raised when a notification cannot be stored because its idempotency key belongs to one that
 * already exists. Keeps the storage technology from leaking inward: the adapter catches its own
 * stack's uniqueness violation and hands this over instead.
 */
public class IdempotencyKeyAlreadyUsedException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyAlreadyUsedException(String idempotencyKey, Throwable cause) {
        super("idempotency key already used: " + idempotencyKey, cause);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
