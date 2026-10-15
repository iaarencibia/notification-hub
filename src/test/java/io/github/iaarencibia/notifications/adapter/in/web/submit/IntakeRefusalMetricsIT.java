package io.github.iaarencibia.notifications.adapter.in.web.submit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Which refusals of the intake endpoint are counted, under which reason, and how many times. */
@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureMockMvc
class IntakeRefusalMetricsIT extends IntegrationTestSupport {

    private static final String PATH = "/api/v1/notifications";
    private static final String CREDENTIAL = "ApiKey " + API_KEY;

    private static final String VALID_BODY =
            """
            {
              "recipient": "https://destination.test/hook",
              "channel": "SERVICE",
              "subject": "Your order shipped",
              "body": "It is on its way.",
              "priority": "HIGH",
              "metadata": {}
            }
            """;

    @Autowired private MockMvc mockMvc;

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("has a series for every reason from startup")
    void registersEveryReason() {
        assertThat(refusals().keySet())
                .containsExactlyInAnyOrder(
                        "invalid_body",
                        "unreadable_body",
                        "invalid_header",
                        "unsupported_media_type",
                        "not_acceptable",
                        "undeliverable_channel",
                        "unacceptable_recipient",
                        "idempotency_key_reused",
                        "unauthenticated");
    }

    @Test
    @DisplayName("counts a body that fails validation as invalid_body, once")
    void countsAnInvalidBody() throws Exception {
        assertCountedOnce("invalid_body", submit("{}"), status().isBadRequest());
    }

    @Test
    @DisplayName("counts a body that is not JSON as unreadable_body, once")
    void countsAnUnreadableBody() throws Exception {
        assertCountedOnce("unreadable_body", submit("not json"), status().isBadRequest());
    }

    @Test
    @DisplayName("counts a body whose enum value does not exist as unreadable_body, once")
    void countsAnUnknownEnumValue() throws Exception {
        assertCountedOnce(
                "unreadable_body",
                submit(VALID_BODY.replace("\"HIGH\"", "\"URGENT\"")),
                status().isBadRequest());
    }

    @Test
    @DisplayName("counts a refused header as invalid_header, once, even with two of them wrong")
    void countsAnInvalidHeader() throws Exception {
        assertCountedOnce(
                "invalid_header",
                submit(VALID_BODY)
                        .header("X-Correlation-Id", "order 4471")
                        .header("Idempotency-Key", "   "),
                status().isBadRequest());
    }

    @Test
    @DisplayName("counts a body that is not JSON by its media type as unsupported_media_type, once")
    void countsAnUnsupportedMediaType() throws Exception {
        assertCountedOnce(
                "unsupported_media_type",
                post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY),
                status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("counts a caller that accepts no JSON answer as not_acceptable, once")
    void countsANotAcceptableRequest() throws Exception {
        assertCountedOnce(
                "not_acceptable",
                submit(VALID_BODY).accept(MediaType.TEXT_PLAIN),
                status().isNotAcceptable());
    }

    @Test
    @DisplayName("counts a channel nothing here can deliver as undeliverable_channel, once")
    void countsAnUndeliverableChannel() throws Exception {
        assertCountedOnce(
                "undeliverable_channel",
                submit(VALID_BODY.replace("\"SERVICE\"", "\"EMAIL\"")),
                status().isBadRequest());
    }

    @Test
    @DisplayName("counts a recipient the policy refuses as unacceptable_recipient, once")
    void countsAnUnacceptableRecipient() throws Exception {
        assertCountedOnce(
                "unacceptable_recipient",
                submit(VALID_BODY.replace("destination.test", "localhost")),
                status().isBadRequest());
    }

    @Test
    @DisplayName("counts a key reused for another payload as idempotency_key_reused, once")
    void countsAReusedKey() throws Exception {
        String key = "it-refused-" + UUID.randomUUID();
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", key))
                .andExpect(status().isAccepted());

        assertCountedOnce(
                "idempotency_key_reused",
                submit(VALID_BODY.replace("Your order shipped", "Your order was cancelled"))
                        .header("Idempotency-Key", key),
                status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("counts a caller without a credential as unauthenticated, once")
    void countsAnUnauthenticatedCaller() throws Exception {
        assertCountedOnce(
                "unauthenticated",
                post(PATH).contentType(MediaType.APPLICATION_JSON).content("not json"),
                status().isUnauthorized());
    }

    @Test
    @DisplayName("counts nothing for an accepted notification or a replay")
    void countsNoAcceptance() throws Exception {
        String key = "it-accepted-" + UUID.randomUUID();
        Map<String, Double> before = refusals();

        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", key))
                .andExpect(status().isAccepted());
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", key))
                .andExpect(status().isAccepted());

        assertThat(refusals()).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "counts nothing for refusals of the query endpoint, the actuator or another method")
    void countsNoOtherEndpoint() throws Exception {
        Map<String, Double> before = refusals();

        mockMvc.perform(get(PATH + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get(PATH + "/" + UUID.randomUUID())
                                .header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(PATH + "/not-a-uuid").header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isMethodNotAllowed());

        assertThat(refusals()).isEqualTo(before);
    }

    private void assertCountedOnce(String reason, RequestBuilder request, ResultMatcher expected)
            throws Exception {
        Map<String, Double> before = refusals();

        mockMvc.perform(request).andExpect(expected);

        Map<String, Double> after = refusals();
        Map<String, Double> expectedAfter = new TreeMap<>(before);
        expectedAfter.merge(reason, 1.0, Double::sum);
        assertThat(after).isEqualTo(expectedAfter);
    }

    private Map<String, Double> refusals() {
        Map<String, Double> byReason = new TreeMap<>();
        for (Counter counter : registry.find("notifications.refused").counters()) {
            byReason.put(counter.getId().getTag("reason"), counter.count());
        }
        return byReason;
    }

    private static MockHttpServletRequestBuilder submit(String body) {
        return post(PATH)
                .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
