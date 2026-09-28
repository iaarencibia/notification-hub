package io.github.iaarencibia.notifications.adapter.in.web;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * The body of a request to send a notification.
 *
 * <p>Separate from {@link NotificationPayload} even though the fields line up today: this one
 * answers to whatever the published API promised its callers, that one to the domain's own
 * invariants. Merging them would let a domain rename silently break every client.
 *
 * <p>Every limit here is the domain's public constant, measured the way the domain and the column
 * measure it -- in characters, which is what {@link CharacterLength} is for.
 *
 * @param body allowed to be empty -- a subject-only ping is a real notification -- but not absent
 * @param metadata optional; absent is read as none rather than rejected, and bounded in entry
 *     count, key length and value length
 */
record SubmitNotificationRequest(
        @NotBlank(message = "must not be blank")
                @CharacterLength(max = NotificationPayload.MAX_RECIPIENT_LENGTH)
                String recipient,
        @NotNull(message = "must be present") Channel channel,
        @NotBlank(message = "must not be blank")
                @CharacterLength(max = NotificationPayload.MAX_SUBJECT_LENGTH)
                String subject,
        @NotNull(message = "must be present, though it may be empty")
                @CharacterLength(max = NotificationPayload.MAX_BODY_LENGTH)
                String body,
        @NotNull(message = "must be present") Priority priority,
        @Size(
                        max = NotificationPayload.MAX_METADATA_ENTRIES,
                        message = "must not exceed {max} entries")
                Map<
                                @CharacterLength(max = NotificationPayload.MAX_METADATA_KEY_LENGTH)
                                String,
                                @NotNull(message = "must not be null")
                                @CharacterLength(
                                        max = NotificationPayload.MAX_METADATA_VALUE_LENGTH)
                                String>
                        metadata) {

    NotificationPayload toPayload() {
        return new NotificationPayload(recipient, channel, subject, body, priority, metadata);
    }
}
