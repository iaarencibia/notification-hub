package io.github.iaarencibia.notifications.adapter.in.web.query;

import io.github.iaarencibia.notifications.application.port.in.query.GetNotificationUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The query endpoint: a notification's lifecycle, read back by its identifier. */
@RestController
@RequestMapping(path = "/api/v1/notifications")
class NotificationQueryController {

    private final GetNotificationUseCase getNotification;

    NotificationQueryController(GetNotificationUseCase getNotification) {
        this.getNotification = getNotification;
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a notification and its delivery attempts")
    @ApiResponse(responseCode = "200", description = "The notification's current lifecycle")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID")
    @ApiResponse(responseCode = "404", description = "No notification has that id")
    NotificationDetailsResponse get(@PathVariable UUID id) {
        return NotificationDetailsResponse.from(getNotification.get(id));
    }
}
