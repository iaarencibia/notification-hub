package io.github.iaarencibia.notifications.adapter.in.web.submit;

import java.util.UUID;

/**
 * What a caller gets back for a notification the service accepted: only the identifier. The
 * response is {@code 202}, meaning the notification was taken and will be attempted, so reporting a
 * state alongside it would describe something that has not happened yet.
 *
 * @param id identifies the notification for the rest of its life
 */
record SubmitNotificationResponse(UUID id) {}
