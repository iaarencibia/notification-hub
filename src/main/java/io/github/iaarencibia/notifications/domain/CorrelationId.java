package io.github.iaarencibia.notifications.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Groups every notification produced by one logical operation. Several notifications share one,
 * unlike {@code idempotency_key}, whose index is unique.
 */
public record CorrelationId(String value) {

    /** Mirrors the width of {@code notification.correlation_id}. */
    public static final int MAX_LENGTH = 128;

    /**
     * Restricted to what identifiers need: the value is written into a structured log line on every
     * attempt, so a newline would let a caller inject log entries of its own.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]+");

    public CorrelationId {
        Objects.requireNonNull(value, "correlationId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "correlationId may only contain letters, digits and the characters . _ : -");
        }
        // Character set check already ruled out non-ASCII, so one UTF-16 unit is one
        // character here.
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "correlationId must not exceed " + MAX_LENGTH + " characters");
        }
    }

    /** For callers that supply none, so the column is never without a value. */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
