package io.github.iaarencibia.notifications.adapter.in.web.submit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubmitNotificationRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static SubmitNotificationRequest request(
            String recipient, String subject, String body, Map<String, String> metadata) {
        return new SubmitNotificationRequest(
                recipient, Channel.SERVICE, subject, body, Priority.HIGH, metadata);
    }

    private static Set<String> pathsOf(SubmitNotificationRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("accepts a request with no NUL character and no blank key")
    void acceptsACleanRequest() {
        assertThat(pathsOf(request("https://a.test", "s", "b", Map.of("k", "v")))).isEmpty();
    }

    @Test
    @DisplayName("refuses a NUL character in the recipient")
    void refusesNulInTheRecipient() {
        assertThat(pathsOf(request("https://a\u0000.test", "s", "b", null)))
                .containsExactly("recipient");
    }

    @Test
    @DisplayName("refuses a NUL character in the subject")
    void refusesNulInTheSubject() {
        assertThat(pathsOf(request("https://a.test", "s\u0000", "b", null)))
                .containsExactly("subject");
    }

    @Test
    @DisplayName("refuses a NUL character in the body")
    void refusesNulInTheBody() {
        assertThat(pathsOf(request("https://a.test", "s", "\u0000", null))).containsExactly("body");
    }

    @Test
    @DisplayName("refuses a NUL character in a metadata value and names the entry")
    void refusesNulInAMetadataValue() {
        assertThat(pathsOf(request("https://a.test", "s", "b", Map.of("k", "v\u0000"))))
                .containsExactly("metadata[k].<map value>");
    }

    @Test
    @DisplayName("refuses a NUL character in a metadata key")
    void refusesNulInAMetadataKey() {
        assertThat(pathsOf(request("https://a.test", "s", "b", Map.of("k\u0000", "v")))).hasSize(1);
    }

    @Test
    @DisplayName("refuses an empty and a blank metadata key")
    void refusesABlankMetadataKey() {
        assertThat(pathsOf(request("https://a.test", "s", "b", Map.of("", "v")))).hasSize(1);
        assertThat(pathsOf(request("https://a.test", "s", "b", Map.of("  ", "v")))).hasSize(1);
    }
}
