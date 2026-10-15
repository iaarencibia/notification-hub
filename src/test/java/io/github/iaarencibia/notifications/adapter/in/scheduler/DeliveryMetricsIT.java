package io.github.iaarencibia.notifications.adapter.in.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.in.dispatch.DispatchDueNotificationsUseCase;
import io.github.iaarencibia.notifications.application.port.in.observe.ObserveQueueDepthUseCase;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationCommand;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationUseCase;
import io.github.iaarencibia.notifications.domain.AttemptOutcome;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.CorrelationId;
import io.github.iaarencibia.notifications.domain.NotificationPayload;
import io.github.iaarencibia.notifications.domain.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** What a real intake and a real delivery leave on the Prometheus endpoint. */
@SpringBootTest(
        properties = {
            IntegrationTestSupport.API_KEY_PROPERTY,
            "notifications.channels.service.allowed-hosts=localhost"
        })
@AutoConfigureObservability
@AutoConfigureMockMvc
class DeliveryMetricsIT extends IntegrationTestSupport {

    private static final WireMockServer DESTINATION = new WireMockServer(options().dynamicPort());

    @Autowired private SubmitNotificationUseCase intake;

    @Autowired private DispatchDueNotificationsUseCase dispatcher;

    @Autowired private ObserveQueueDepthUseCase queueDepth;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private MockMvc mockMvc;

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
    @DisplayName("exports the intake, the attempt, its duration and the notification settled SENT")
    void exportsASuccessfulDelivery() throws Exception {
        DESTINATION.stubFor(post(urlEqualTo("/hook/ok")).willReturn(aResponse().withStatus(202)));
        String before = scrape();
        submitTo("/hook/ok");

        awaitIncrease(
                before,
                "notifications_accepted_total",
                "channel=\"SERVICE\"",
                "priority=\"MEDIUM\"");
        awaitIncrease(
                before,
                "notifications_attempts_total",
                "channel=\"SERVICE\"",
                "outcome=\"SUCCESS\"");
        awaitIncrease(
                before,
                "notifications_delivery_duration_seconds_count",
                "channel=\"SERVICE\"",
                "outcome=\"SUCCESS\"");
        awaitIncrease(before, "notifications_claims_total", "channel=\"SERVICE\"");
        awaitIncrease(
                before,
                "notifications_delivery_duration_seconds_bucket",
                "channel=\"SERVICE\"",
                "outcome=\"SUCCESS\"",
                "le=\"+Inf\"");
        awaitIncrease(
                before, "notifications_settled_total", "channel=\"SERVICE\"", "result=\"sent\"");
    }

    @Test
    @DisplayName("exports the notification a destination refused as settled FAILED")
    void exportsAFailedDelivery() throws Exception {
        DESTINATION.stubFor(
                post(urlEqualTo("/hook/broken")).willReturn(aResponse().withStatus(400)));
        String before = scrape();
        submitTo("/hook/broken");

        awaitIncrease(
                before,
                "notifications_attempts_total",
                "channel=\"SERVICE\"",
                "outcome=\"PERMANENT_FAILURE\"");
        awaitIncrease(
                before, "notifications_settled_total", "channel=\"SERVICE\"", "result=\"failed\"");
    }

    @Test
    @DisplayName("exports the rows waiting and in flight, read from the database on a refresh")
    void exportsTheQueueDepth() throws Exception {
        submitTo("/hook/waiting");
        submitTo("/hook/waiting");

        queueDepth.observeQueueDepth();

        assertThat(samples(scrape(), "notifications_queue_rows", "status=\"PENDING\""))
                .containsExactly(2.0);
        assertThat(samples(scrape(), "notifications_queue_rows", "status=\"DISPATCHING\""))
                .containsExactly(0.0);
    }

    @Test
    @DisplayName("exports every series of a channel at zero before any traffic reaches it")
    void exportsUntouchedSeriesAtZero() throws Exception {
        String exported = scrape();
        String log = "channel=\"LOG\"";

        for (Priority priority : Priority.values()) {
            assertThat(
                            samples(
                                    exported,
                                    "notifications_accepted_total",
                                    log,
                                    "priority=\"" + priority.name() + "\""))
                    .containsExactly(0.0);
        }
        for (AttemptOutcome outcome : AttemptOutcome.values()) {
            String label = "outcome=\"" + outcome.name() + "\"";
            assertThat(samples(exported, "notifications_attempts_total", log, label))
                    .containsExactly(0.0);
            assertThat(
                            samples(
                                    exported,
                                    "notifications_delivery_duration_seconds_count",
                                    log,
                                    label))
                    .containsExactly(0.0);
        }
        for (String result : List.of("sent", "failed")) {
            assertThat(
                            samples(
                                    exported,
                                    "notifications_settled_total",
                                    log,
                                    "result=\"" + result + "\""))
                    .containsExactly(0.0);
        }
        for (String kind : List.of("claim_lost", "unexpected")) {
            assertThat(
                            samples(
                                    exported,
                                    "notifications_dispatch_errors_total",
                                    "kind=\"" + kind + "\""))
                    .containsExactly(0.0);
        }
        assertThat(samples(exported, "notifications_replayed_total", log)).containsExactly(0.0);
        assertThat(samples(exported, "notifications_claims_total", log)).containsExactly(0.0);
        assertThat(samples(exported, "notifications_retries_total", log)).containsExactly(0.0);
        assertThat(exported).doesNotContain("channel=\"EMAIL\"");
    }

    @Test
    @DisplayName("exports a repeated idempotency key as a replay under its channel")
    void exportsAReplay() throws Exception {
        String before = scrape();
        submitTo("/hook/replayed", "key-replayed");
        submitTo("/hook/replayed", "key-replayed");

        double baseline = single(before, "notifications_replayed_total", "channel=\"SERVICE\"");
        assertThat(single(scrape(), "notifications_replayed_total", "channel=\"SERVICE\""))
                .isEqualTo(baseline + 1);
    }

    private void awaitIncrease(String before, String metric, String... labels) throws Exception {
        double baseline = single(before, metric, labels);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            dispatcher.dispatchDue();
            if (single(scrape(), metric, labels) >= baseline + 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(metric + " with " + List.of(labels) + " never increased");
    }

    private static double single(String exported, String metric, String... labels) {
        List<Double> found = samples(exported, metric, labels);
        assertThat(found).as("%s with %s", metric, List.of(labels)).hasSize(1);
        return found.getFirst();
    }

    private String scrape() throws Exception {
        return mockMvc.perform(
                        get("/actuator/prometheus")
                                .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static List<Double> samples(String exported, String metric, String... labels) {
        return exported.lines()
                .filter(line -> line.startsWith(metric + "{"))
                .filter(line -> Arrays.stream(labels).allMatch(line::contains))
                .map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .toList();
    }

    private void submitTo(String path) {
        submitTo(path, "key-" + UUID.randomUUID());
    }

    private void submitTo(String path, String idempotencyKey) {
        NotificationPayload payload =
                new NotificationPayload(
                        DESTINATION.baseUrl() + path,
                        Channel.SERVICE,
                        "a subject",
                        "a body",
                        Priority.MEDIUM,
                        Map.of());

        intake.submit(
                new SubmitNotificationCommand(
                        payload, new CorrelationId("order-4471"), idempotencyKey));
    }
}
