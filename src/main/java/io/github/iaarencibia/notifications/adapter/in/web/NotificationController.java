package io.github.iaarencibia.notifications.adapter.in.web;

import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.Notification;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
class NotificationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final SubmitNotificationUseCase submitNotification;

    NotificationController(SubmitNotificationUseCase submitNotification) {
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
    ResponseEntity<SubmitNotificationResponse> submit(
            @Valid @RequestBody SubmitNotificationRequest request,
            @RequestHeader(name = CORRELATION_ID_HEADER, required = false) String correlationHeader,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String keyHeader) {

        // Both headers are read before either refusal is raised, so a caller that got two wrong
        // hears about both at once instead of round-tripping twice.
        Map<String, String> refusals = new TreeMap<>();
        CorrelationId correlationId = correlationId(correlationHeader, refusals);
        String key = idempotencyKey(keyHeader, refusals);
        if (!refusals.isEmpty()) {
            throw new InvalidRequestException(refusals);
        }

        UUID id =
                submitNotification.submit(
                        new SubmitNotificationCommand(request.toPayload(), correlationId, key));

        // The correlation identifier is not echoed: the response carries the notification's own
        // id, and the header exists to group several notifications of one operation, a grouping
        // only the sending caller knows.
        return ResponseEntity.accepted().body(new SubmitNotificationResponse(id));
    }

    // The reasons are worded here rather than forwarded from the domain: the domain explains
    // itself to a Java caller with a bad argument, this to someone holding an HTTP client. Only
    // the limits are shared, because those must not drift.
    private static CorrelationId correlationId(String header, Map<String, String> refusals) {
        if (header == null) {
            // The column is NOT NULL, and a grouping with holes in it groups nothing.
            return CorrelationId.generate();
        }
        try {
            return new CorrelationId(header);
        } catch (IllegalArgumentException rejected) {
            refusals.put(
                    CORRELATION_ID_HEADER,
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
