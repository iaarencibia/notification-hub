package io.github.iaarencibia.notifications.adapter.in.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.github.iaarencibia.notifications.application.DeliverableChannels;
import io.github.iaarencibia.notifications.application.port.in.query.NotificationNotFoundException;
import io.github.iaarencibia.notifications.application.port.in.submit.IdempotencyKeyReusedException;
import io.github.iaarencibia.notifications.application.port.in.submit.UnacceptableRecipientException;
import io.github.iaarencibia.notifications.application.port.in.submit.UndeliverableChannelException;
import io.github.iaarencibia.notifications.domain.Channel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns everything that can go wrong inside the dispatcher servlet into a problem detail.
 *
 * <p>The authentication entry point hands over the refusals born in the filter chain, before a
 * request reaches a controller, so they are answered here too.
 *
 * <p>Extending Spring's own handler means the rest of the surface -- wrong method, wrong media
 * type, missing parameter -- arrives in that shape too, not only the cases named here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String API_KEY_SCHEME = "ApiKey";

    private final DeliverableChannels deliverableChannels;

    /**
     * @param deliverableChannels the same set the use case decides with, held only to describe it
     *     in a refusal -- nothing here decides anything
     */
    ApiExceptionHandler(DeliverableChannels deliverableChannels) {
        this.deliverableChannels = deliverableChannels;
    }

    /**
     * A field that failed validation is reported by name with what it needed; no field value is
     * echoed back. A violation inside {@code metadata} is reported as {@code metadata[<key>]} so a
     * caller holding thirty-two entries knows which one to fix -- only the key travels, never the
     * value.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        // Sorted sets, not merged as they arrive: Bean Validation does not guarantee violation
        // order, so a field breaking two rules at once could be described differently from run
        // to run.
        Map<String, SortedSet<String>> byName = new TreeMap<>();
        exception
                .getFieldErrors()
                .forEach(
                        error ->
                                byName.computeIfAbsent(error.getField(), field -> new TreeSet<>())
                                        .add(String.valueOf(error.getDefaultMessage())));
        // No global constraint exists today, but reading them anyway keeps the first one ever
        // added from being answered with a 400 whose error map names nothing.
        exception
                .getGlobalErrors()
                .forEach(
                        error ->
                                byName.computeIfAbsent(
                                                error.getObjectName(), object -> new TreeSet<>())
                                        .add(String.valueOf(error.getDefaultMessage())));

        Map<String, String> errors = new TreeMap<>();
        byName.forEach((name, messages) -> errors.put(name, String.join("; ", messages)));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "One or more fields of the request body are invalid");
        problem.setProperty("errors", errors);

        return handleExceptionInternal(
                exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Covers a body that is not JSON at all and, more usefully, one where an enum carries a value
     * the service does not have. Jackson fails that before validation runs, so without this the
     * caller would be told only that the body was unreadable.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "The request body could not be read as JSON");

        if (exception.getCause() instanceof InvalidFormatException invalidValue) {
            problem.setProperty("errors", Map.of(fieldOf(invalidValue), reasonFor(invalidValue)));
        }

        return handleExceptionInternal(
                exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Answers the headers the endpoint refused, each reported under its own name -- what the caller
     * has to go change -- and all of them at once, for the same reason the body path names every
     * field.
     */
    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidHeader(InvalidRequestException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "One or more request headers are invalid");
        problem.setProperty("errors", exception.reasonsByHeader());
        return problem;
    }

    /**
     * Answers a channel this deployment has no adapter for, reported under {@code channel} -- the
     * same field name Bean Validation would use -- since which layer decided the refusal is this
     * service's business, not the caller's.
     */
    @ExceptionHandler(UndeliverableChannelException.class)
    ProblemDetail handleUndeliverableChannel(UndeliverableChannelException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "One or more fields of the request body are invalid");
        problem.setProperty(
                "errors",
                Map.of("channel", "must be one of " + exception.deliverable().describe()));
        return problem;
    }

    @ExceptionHandler(UnacceptableRecipientException.class)
    ProblemDetail handleUnacceptableRecipient(UnacceptableRecipientException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "One or more fields of the request body are invalid");
        problem.setProperty("errors", Map.of("recipient", exception.reason()));
        return problem;
    }

    /**
     * The wording is the same whether the credential was absent or simply wrong, so a caller
     * guessing keys cannot learn from the response which half it got right.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication() {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED,
                        "A valid credential using the "
                                + API_KEY_SCHEME
                                + " scheme is required in the Authorization header");
        // RFC 9110 requires a 401 to carry a challenge; naming the scheme is also the only way a
        // caller learns what to send.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        API_KEY_SCHEME + " realm=\"notification-service\"")
                .body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "One or more request parameters are invalid");
        String reason =
                exception.getRequiredType() == UUID.class
                        ? "must be a UUID"
                        : "is not of the expected type";
        problem.setProperty("errors", Map.of(String.valueOf(exception.getPropertyName()), reason));

        return handleExceptionInternal(
                exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ProblemDetail handleNotificationNotFound(NotificationNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No notification exists with the id " + exception.id());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The Idempotency-Key was already used for a different notification");
        problem.setProperty("idempotencyKey", exception.idempotencyKey());
        problem.setProperty("notificationId", exception.existingId().toString());
        return problem;
    }

    /**
     * The last resort, so no caller ever receives the container's own error page in place of a
     * problem detail. The cause goes to the log, not the response: an unhandled failure is the
     * worst place to start describing the inside of the service.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleAnythingElse(Exception exception, HttpServletRequest request) {
        // Method and path travel with the stack trace: the one log line that most needs to say
        // where something failed is the one the caller's response deliberately withholds.
        log.error(
                "Unhandled failure while serving {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
    }

    private static String fieldOf(InvalidFormatException invalidValue) {
        return invalidValue.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null)
                .collect(Collectors.joining("."));
    }

    /**
     * Names the values a caller may send. For {@link Channel} that is the deliverable set, not the
     * enum's constants: a channel parses without being deliverable, and answering with the
     * constants would advertise one the next request would refuse.
     */
    private String reasonFor(InvalidFormatException invalidValue) {
        Class<?> expected = invalidValue.getTargetType();
        if (expected == Channel.class) {
            return "must be one of " + deliverableChannels.describe();
        }
        if (expected != null && expected.isEnum()) {
            String allowed =
                    Arrays.stream(expected.getEnumConstants())
                            .map(String::valueOf)
                            .collect(Collectors.joining(", "));
            return "must be one of " + allowed;
        }
        return "is not of the expected type";
    }
}
