package io.github.iaarencibia.notifications.adapter.in.web.submit;

import io.github.iaarencibia.notifications.adapter.in.web.CorrelationIdFilter;
import io.github.iaarencibia.notifications.adapter.in.web.InvalidRequestException;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The intake endpoint: the front door of the service.
 *
 * <p>It does three things and stops: turns a request into a command, hands it to the use case,
 * turns the answer back into a response. No decision about notifications is taken here, which is
 * why nothing below this package knows that HTTP exists.
 *
 * <p>The answer is {@code 202 Accepted}, never {@code 201 Created} -- not because nothing was
 * created, since the row is, but because what the caller has to know is that the delivery it asked
 * for has not happened yet.
 */
@RestController
@RequestMapping(path = "/api/v1/notifications")
class SubmitNotificationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(SubmitNotificationController.class);

    private final SubmitNotificationUseCase submitNotification;

    SubmitNotificationController(SubmitNotificationUseCase submitNotification) {
        this.submitNotification = submitNotification;
    }

    /**
     * Accepts a notification for delivery. A repeated {@code Idempotency-Key} is answered exactly
     * like the request that first used it, with the same identifier and status, and creates
     * nothing.
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit a notification for delivery")
    @ApiResponse(responseCode = "202", description = "Accepted; delivery has not happened yet")
    @ApiResponse(responseCode = "400", description = "The body or a header is invalid")
    @ApiResponse(
            responseCode = "422",
            description = "The Idempotency-Key was already used for a different notification")
    ResponseEntity<SubmitNotificationResponse> submit(
            @Valid @RequestBody SubmitNotificationRequest request,
            @Parameter(description = "Groups the notifications of one operation in the logs")
                    @RequestHeader(
                            name = CorrelationIdFilter.CORRELATION_ID_HEADER,
                            required = false)
                    String correlationHeader,
            @Parameter(description = "Makes a retry of this request create nothing new")
                    @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
                    String keyHeader,
            @RequestAttribute(name = CorrelationIdFilter.REQUEST_ATTRIBUTE, required = false)
                    CorrelationId filterCorrelationId) {

        // Both headers are read before either refusal is raised, so a caller that got two wrong
        // hears about both at once instead of round-tripping twice.
        Map<String, String> refusals = new TreeMap<>();
        CorrelationId correlationId =
                correlationId(correlationHeader, filterCorrelationId, refusals);
        String key = idempotencyKey(keyHeader, refusals);
        if (!refusals.isEmpty()) {
            throw new InvalidRequestException(refusals);
        }

        UUID id =
                submitNotification.submit(
                        new SubmitNotificationCommand(request.toPayload(), correlationId, key));

        log.atInfo()
                .addKeyValue("notificationId", id)
                .addKeyValue("channel", request.channel())
                .addKeyValue("priority", request.priority())
                .setMessage("notification accepted")
                .log();

        // The correlation identifier is not echoed: the response carries the notification's own
        // id, and the header exists to group several notifications of one operation, a grouping
        // only the sending caller knows.
        return ResponseEntity.accepted().body(new SubmitNotificationResponse(id));
    }

    // The reasons are worded here rather than forwarded from the domain: the domain explains
    // itself to a Java caller with a bad argument, this to someone holding an HTTP client. Only
    // the limits are shared, because those must not drift.
    private static CorrelationId correlationId(
            String header, CorrelationId fromFilter, Map<String, String> refusals) {
        if (header == null) {
            return fromFilter;
        }
        try {
            return new CorrelationId(header);
        } catch (IllegalArgumentException rejected) {
            refusals.put(
                    CorrelationIdFilter.CORRELATION_ID_HEADER,
                    "must be 1 to "
                            + CorrelationId.MAX_LENGTH
                            + " characters, and may contain "
                            + "only letters, digits and the characters . _ : -");
            return null;
        }
    }

    private static String idempotencyKey(String header, Map<String, String> refusals) {
        if (header == null) {
            return null;
        }
        // Blank is refused rather than read as absent: the empty string is not null, so the
        // partial unique index would store it as a real key and every caller making the same
        // mistake would collide with all the others.
        if (header.isBlank()) {
            refusals.put(
                    IDEMPOTENCY_KEY_HEADER,
                    "must not be blank; omit the header rather than sending it empty");
            return null;
        }
        if (header.length() > Notification.MAX_IDEMPOTENCY_KEY_LENGTH) {
            refusals.put(
                    IDEMPOTENCY_KEY_HEADER,
                    "must not exceed " + Notification.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            return null;
        }
        return header;
    }
}
