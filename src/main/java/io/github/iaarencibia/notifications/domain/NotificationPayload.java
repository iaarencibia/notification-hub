package io.github.iaarencibia.notifications.domain;

import java.util.Map;
import java.util.Objects;

/**
 * The client-supplied half of a notification, grouped as the schema groups it.
 *
 * @param recipient where the notification is delivered: a URL for the SERVICE channel, an address
 *     for the others
 */
public record NotificationPayload(
        String recipient,
        Channel channel,
        String subject,
        String body,
        Priority priority,
        Map<String, String> metadata) {

    /** Mirrors the width of {@code notification.recipient}. */
    public static final int MAX_RECIPIENT_LENGTH = 2048;

    /** Mirrors the width of {@code notification.subject}. */
    public static final int MAX_SUBJECT_LENGTH = 512;

    /** Mirrors the width of {@code notification.body}. */
    public static final int MAX_BODY_LENGTH = 16384;

    public static final int MAX_METADATA_ENTRIES = 32;

    /**
     * A metadata key is an identifier, and this is the width {@code correlation_id} already uses.
     */
    public static final int MAX_METADATA_KEY_LENGTH = 128;

    /**
     * Mirrors {@link #MAX_RECIPIENT_LENGTH} so a URL accepted as a recipient is not refused as a
     * metadata value under a different limit.
     */
    public static final int MAX_METADATA_VALUE_LENGTH = MAX_RECIPIENT_LENGTH;

    public NotificationPayload {
        recipient = requireText(recipient, "recipient", MAX_RECIPIENT_LENGTH);
        subject = requireText(subject, "subject", MAX_SUBJECT_LENGTH);

        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        // Bounded like the others but may be empty: a subject-only ping is legitimate. Still
        // required, so its absence is never silent.
        Objects.requireNonNull(body, "body must not be null");
        requireWithin(body, "body", MAX_BODY_LENGTH);

        // Absent metadata becomes the empty map, matching DEFAULT '{}'::JSONB.
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        requireBoundedMetadata(metadata);
    }

    private static void requireBoundedMetadata(Map<String, String> metadata) {
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                    "metadata must not exceed " + MAX_METADATA_ENTRIES + " entries");
        }
        metadata.forEach(
                (key, value) -> {
                    requireText(key, "metadata key", MAX_METADATA_KEY_LENGTH);
                    // Named with its key so a caller holding many entries knows which one to
                    // shorten.
                    requireWithin(
                            value, "metadata value for '" + key + "'", MAX_METADATA_VALUE_LENGTH);
                });
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return requireWithin(value, field, maxLength);
    }

    /**
     * Counted in code points, not UTF-16 units, because that is how PostgreSQL counts: an emoji is
     * one character there and two for {@code String.length()}. Counting units would reject values
     * the column accepts.
     */
    private static String requireWithin(String value, String field, int maxLength) {
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a NUL character");
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }
}
