package io.github.iaarencibia.notifications.adapter.in.web.submit;

import io.github.iaarencibia.notifications.adapter.in.web.CharacterLength;
import io.github.iaarencibia.notifications.adapter.in.web.NoNulCharacter;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
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
                @NoNulCharacter
                String recipient,
        @NotNull(message = "must be present") Channel channel,
        @NotBlank(message = "must not be blank")
                @CharacterLength(max = NotificationPayload.MAX_SUBJECT_LENGTH)
                @NoNulCharacter
                String subject,
        @NotNull(message = "must be present, though it may be empty")
                @Schema(description = "May be empty, but not absent")
                @CharacterLength(max = NotificationPayload.MAX_BODY_LENGTH)
                @NoNulCharacter
                String body,
        @NotNull(message = "must be present") Priority priority,
        @Schema(
                        description =
                                "Optional; absent is read as none. Keys at most "
                                        + NotificationPayload.MAX_METADATA_KEY_LENGTH
                                        + " characters, values at most "
                                        + NotificationPayload.MAX_METADATA_VALUE_LENGTH
                                        + " characters",
                        maxProperties = NotificationPayload.MAX_METADATA_ENTRIES)
                @Size(
                        max = NotificationPayload.MAX_METADATA_ENTRIES,
                        message = "must not exceed {max} entries")
                Map<
                                @NotBlank(message = "must not be blank")
                                @CharacterLength(max = NotificationPayload.MAX_METADATA_KEY_LENGTH)
                                @NoNulCharacter String,
                                @NotNull(message = "must not be null")
                                @CharacterLength(
                                        max = NotificationPayload.MAX_METADATA_VALUE_LENGTH)
                                @NoNulCharacter String>
                        metadata) {

    NotificationPayload toPayload() {
        return new NotificationPayload(recipient, channel, subject, body, priority, metadata);
    }
}
