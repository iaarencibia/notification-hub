package io.github.iaarencibia.notifications.adapter.in.web.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.in.dispatch.DispatchDueNotificationsUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
        properties = {
            IntegrationTestSupport.API_KEY_PROPERTY,
            "notifications.channels.service.allowed-hosts=localhost"
        })
@AutoConfigureMockMvc
class NotificationQueryIT extends IntegrationTestSupport {

    private static final String PATH = "/api/v1/notifications";
    private static final String CREDENTIAL = "ApiKey " + API_KEY;

    private static final WireMockServer DESTINATION = new WireMockServer(options().dynamicPort());

    private static final Set<String> RESPONSE_FIELDS =
            Set.of(
                    "id",
                    "channel",
                    "recipient",
                    "status",
                    "attempts",
                    "maxAttempts",
                    "nextAttemptAt",
                    "lastError",
                    "createdAt",
                    "updatedAt",
                    "sentAt",
                    "correlationId",
                    "history");

    private static final Set<String> ATTEMPT_FIELDS =
            Set.of("number", "at", "outcome", "statusCode", "error");

    @Autowired private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private DispatchDueNotificationsUseCase dispatcher;

    @BeforeAll
    static void startTheDestination() {
        DESTINATION.start();
    }

    @AfterAll
    static void stopTheDestination() {
        DESTINATION.stop();
    }

    @BeforeEach
    void emptyTheTableAndTheStubs() {
        jdbc.execute("TRUNCATE TABLE notification CASCADE");
        DESTINATION.resetAll();
    }

    @Test
    @DisplayName("returns a notification that was just accepted, with an empty history")
    void returnsANotificationJustAccepted() throws Exception {
        String id = submitTo("/hook/ok", "order-4471");

        mockMvc.perform(getById(id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.channel").value("SERVICE"))
                .andExpect(jsonPath("$.recipient").value(DESTINATION.baseUrl() + "/hook/ok"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.nextAttemptAt").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").value("order-4471"))
                .andExpect(jsonPath("$.history").isArray())
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    @DisplayName("says null, rather than leaving the field out, when a value does not exist yet")
    void serialisesAbsentValuesAsNull() throws Exception {
        String id = submitTo("/hook/ok", "order-4471");

        JsonNode body = bodyOf(getById(id));

        assertThat(body.has("sentAt")).isTrue();
        assertThat(body.get("sentAt").isNull()).isTrue();
        assertThat(body.has("lastError")).isTrue();
        assertThat(body.get("lastError").isNull()).isTrue();
    }

    @Test
    @DisplayName("shows the attempt a real delivery made, with its outcome")
    void showsTheAttemptOfADelivery() throws Exception {
        DESTINATION.stubFor(
                WireMock.post(urlEqualTo("/hook/ok")).willReturn(aResponse().withStatus(202)));
        String id = submitTo("/hook/ok", "order-4471");

        dispatchUntilSettled();

        JsonNode body = bodyOf(getById(id));
        assertThat(body.get("status").asText()).isEqualTo("SENT");
        assertThat(body.get("attempts").asInt()).isEqualTo(1);
        assertThat(body.get("sentAt").isNull()).isFalse();
        assertThat(body.get("lastError").isNull()).isTrue();
        assertThat(body.get("history")).hasSize(1);
        JsonNode attempt = body.get("history").get(0);
        assertThat(attempt.get("number").asInt()).isEqualTo(1);
        assertThat(attempt.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(attempt.get("statusCode").asInt()).isEqualTo(202);
        assertThat(attempt.get("at").isNull()).isFalse();
        assertThat(attempt.get("error").isNull()).isTrue();
    }

    @Test
    @DisplayName("shows why a delivery failed, on the notification and on the attempt")
    void showsWhyADeliveryFailed() throws Exception {
        DESTINATION.stubFor(
                WireMock.post(urlEqualTo("/hook/broken")).willReturn(aResponse().withStatus(400)));
        String id = submitTo("/hook/broken", "order-4471");

        dispatchUntilSettled();

        JsonNode body = bodyOf(getById(id));
        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("lastError").isNull()).isFalse();
        assertThat(body.get("sentAt").isNull()).isTrue();
        JsonNode attempt = body.get("history").get(0);
        assertThat(attempt.get("outcome").asText()).isEqualTo("PERMANENT_FAILURE");
        assertThat(attempt.get("statusCode").asInt()).isEqualTo(400);
        assertThat(attempt.get("error").asText()).isEqualTo(body.get("lastError").asText());
    }

    @Test
    @DisplayName("exposes only the lifecycle: never the subject, the body or the metadata")
    void exposesOnlyTheLifecycle() throws Exception {
        DESTINATION.stubFor(
                WireMock.post(urlEqualTo("/hook/ok")).willReturn(aResponse().withStatus(202)));
        String id = submitTo("/hook/ok", "order-4471");
        dispatchUntilSettled();

        JsonNode body = bodyOf(getById(id));

        assertThat(fieldNamesOf(body)).isEqualTo(RESPONSE_FIELDS);
        assertThat(fieldNamesOf(body.get("history").get(0))).isEqualTo(ATTEMPT_FIELDS);
        assertThat(body.toString())
                .doesNotContain("Your order shipped", "It is on its way.", "orderId");
    }

    @Test
    @DisplayName("answers 404 as a problem detail when no notification has that id")
    void answers404ForAnUnknownId() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(getById(unknown.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("No notification exists with the id " + unknown));
    }

    @Test
    @DisplayName("answers 400 as a problem detail naming the id when it is not a UUID")
    void answers400ForAnIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(getById("not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more request parameters are invalid"))
                .andExpect(jsonPath("$.errors.id").value("must be a UUID"));
    }

    @Test
    @DisplayName("answers 401 when the request carries no credential")
    void answers401WithoutACredential() throws Exception {
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    private void dispatchUntilSettled() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            dispatcher.dispatchDue();
            Integer unfinished =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM notification "
                                    + "WHERE status IN ('PENDING', 'DISPATCHING')",
                            Integer.class);
            if (unfinished != null && unfinished == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no notification reached a terminal state within thirty seconds");
    }

    private String submitTo(String path, String correlationId) throws Exception {
        String body =
                """
                {
                  "recipient": "%s",
                  "channel": "SERVICE",
                  "subject": "Your order shipped",
                  "body": "It is on its way.",
                  "priority": "HIGH",
                  "metadata": {"orderId": "4471"}
                }
                """
                        .formatted(DESTINATION.baseUrl() + path);
        MvcResult result =
                mockMvc.perform(
                                post(PATH)
                                        .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                                        .header("X-Correlation-Id", correlationId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isAccepted())
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static MockHttpServletRequestBuilder getById(String id) {
        return get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, CREDENTIAL);
    }

    private JsonNode bodyOf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> fieldNamesOf(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
