package io.github.iaarencibia.notifications.adapter.out.channel;

import io.github.iaarencibia.notifications.application.port.out.NotificationChannelSender;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.DispatchOutcome;
import io.github.iaarencibia.notifications.domain.Notification;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Delivers a notification by posting it to the URL the client named as the recipient.
 *
 * <p>Classifies the response, which is the whole job here:
 *
 * <ul>
 *   <li><strong>2xx</strong> -- delivered.
 *   <li><strong>3xx</strong> -- the recipient is not the final address, and no retry rewrites it;
 *       redirects are never followed.
 *   <li><strong>5xx, a timeout, a refused connection</strong> -- the destination is unwell, not the
 *       request; retrying is what fixes it.
 *   <li><strong>4xx</strong> -- the destination understood and refused; an identical retry would
 *       produce an identical refusal.
 *   <li><strong>429</strong> -- a 4xx by number and transient in meaning: the destination is asking
 *       for less traffic, and its {@code Retry-After} hint is honoured.
 *   <li><strong>a private or local address</strong> -- never contacted. Permanent when the
 *       recipient names one; retryable when a name resolves to one, as the next lookup may not.
 * </ul>
 *
 * <p>Nothing here retries or sleeps: how many attempts a notification gets belongs to the
 * dispatcher and the retry policy.
 */
class HttpServiceChannelSender implements NotificationChannelSender {

    /** The identifier the destination can use to discard a delivery it has already accepted. */
    static final String NOTIFICATION_ID_HEADER = "X-Notification-Id";

    /** Groups this delivery with the rest of its operation, on the destination's side too. */
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final RestClient restClient;
    private final ServiceDestinationPolicy policy;

    HttpServiceChannelSender(RestClient restClient, ServiceDestinationPolicy policy) {
        this.restClient = restClient;
        this.policy = policy;
    }

    @Override
    public Channel channel() {
        return Channel.SERVICE;
    }

    @Override
    public DispatchOutcome send(Notification notification) {
        URI destination;
        try {
            destination = new URI(notification.payload().recipient());
        } catch (URISyntaxException malformed) {
            return new DispatchOutcome.PermanentFailure(
                    null, "recipient is not a valid URI: " + malformed.getReason());
        }

        // Parsing is not enough: "example.com/hook" is a legal relative URI, and other shapes
        // parse with no HTTP call buildable from them. Each is permanent: the recipient is
        // stored on the row and no retry rewrites it.
        Optional<String> refusal = policy.refuseBeforeConnecting(destination);
        if (refusal.isPresent()) {
            return new DispatchOutcome.PermanentFailure(null, refusal.get());
        }

        try {
            return restClient
                    .post()
                    .uri(destination)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(NOTIFICATION_ID_HEADER, notification.id().toString())
                    .header(CORRELATION_ID_HEADER, notification.correlationId().value())
                    .body(bodyOf(notification))
                    .exchange(
                            (request, response) ->
                                    classify(response.getStatusCode(), response.getHeaders()));
        } catch (ResourceAccessException unreachable) {
            // Connect timeout, read timeout, refused connection, unresolved name: no answer
            // arrived, so there is no status to record, and each is worth trying again.
            return new DispatchOutcome.RetryableFailure(
                    null,
                    refusedAddressIn(unreachable)
                            .map(Throwable::getMessage)
                            .orElseGet(() -> describe(unreachable)),
                    null);
        } catch (RuntimeException refused) {
            // The backstop: this port promises never to throw, and the checks above can only
            // cover shapes already thought of -- twice now a recipient got past them and blew
            // up here. Permanent, since an unchanging recipient will never build a request.
            return new DispatchOutcome.PermanentFailure(
                    null,
                    "recipient cannot be reached as an HTTP destination -- " + describe(refused));
        }
    }

    /**
     * The wire format of the outbound call: only the payload the client submitted travels. The
     * lifecycle is this service's own business.
     */
    private static Map<String, Object> bodyOf(Notification notification) {
        return Map.of(
                "recipient", notification.payload().recipient(),
                "subject", notification.payload().subject(),
                "body", notification.payload().body(),
                "priority", notification.payload().priority().name(),
                "metadata", notification.payload().metadata());
    }

    private static DispatchOutcome classify(HttpStatusCode status, HttpHeaders headers) {
        if (status.is2xxSuccessful()) {
            return new DispatchOutcome.Success(status.value());
        }
        if (status.is3xxRedirection()) {
            String location = headers.getFirst(HttpHeaders.LOCATION);
            return new DispatchOutcome.PermanentFailure(
                    status.value(),
                    "the destination redirected to "
                            + (location == null ? "an unnamed location" : location)
                            + "; the recipient must name the final address");
        }
        if (status.value() == 429) {
            return new DispatchOutcome.RetryableFailure(
                    status.value(),
                    "the destination asked for fewer requests",
                    retryAfterOf(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (status.is4xxClientError()) {
            return new DispatchOutcome.PermanentFailure(
                    status.value(),
                    "the destination rejected the notification with " + status.value());
        }
        // 5xx, and anything else unrecognised: treated as unwell rather than refused, since the
        // expensive mistake is discarding a notification that would have been delivered on the
        // next attempt.
        return new DispatchOutcome.RetryableFailure(
                status.value(), "the destination answered " + status.value(), null);
    }

    /**
     * Reads {@code Retry-After} in its delay-seconds form only; the HTTP-date form is deliberately
     * ignored, since honouring it means trusting the destination's clock against ours. Absent or
     * unreadable gives null, meaning "no instruction," and the retry policy falls back to its own
     * backoff.
     *
     * <p>Zero passes through deliberately: this channel reports the instruction and does not judge
     * it. Capping and flooring belong to the retry policy.
     */
    private static Duration retryAfterOf(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notADelay) {
            return null;
        }
    }

    private static Optional<Throwable> refusedAddressIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof VettingDnsResolver.RefusedAddressException) {
                return Optional.of(cause);
            }
        }
        return Optional.empty();
    }

    /**
     * The class name travels alongside the message: a timeout's message is often just the URL, and
     * a recorded failure that does not say what went wrong answers nothing.
     */
    private static String describe(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
