package io.github.iaarencibia.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.domain.Channel;
import io.github.iaarencibia.notifications.domain.Priority;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The intake endpoint end to end: the real filter chain, the real controller, the real use case and
 * a real PostgreSQL. Most of what it proves is only true when all of it runs together -- a repeated
 * idempotency key returning the first identifier depends on a unique index, an unknown channel
 * being a {@code 400} depends on the exception handler, and reachability at all depends on the
 * security chain.
 *
 * <p>Rows are asserted through their own idempotency key, not by counting the table, so no test
 * here can be made to pass or fail by what another one wrote.
 */
@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureMockMvc
class NotificationIntakeIT extends IntegrationTestSupport {

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
              "metadata": {"orderId": "4471"}
            }
            """;

    @Autowired private MockMvc mockMvc;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private ObjectMapper objectMapper;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private ch.qos.logback.classic.Logger controllerLogger;

    @BeforeEach
    void captureTheControllerLog() {
        controllerLogger =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(NotificationController.class);
        controllerLogger.setLevel(Level.INFO);
        logged.start();
        controllerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseTheControllerLog() {
        controllerLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    @DisplayName("logs the acceptance with the ids, channel and priority, under the caller's id")
    void logsTheAcceptance() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                submit(VALID_BODY)
                                        .header("Idempotency-Key", "it-logged")
                                        .header("X-Correlation-Id", "order-4471"))
                        .andExpect(status().isAccepted())
                        .andReturn();

        ILoggingEvent line = logged.list.getFirst();
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage()).isEqualTo("notification accepted");
        assertThat(fieldsOf(line))
                .containsEntry("notificationId", UUID.fromString(idOf(result)))
                .containsEntry("correlationId", "order-4471")
                .containsEntry("channel", Channel.SERVICE)
                .containsEntry("priority", Priority.HIGH);
        assertThat(line.getMDCPropertyMap())
                .as("the filter is wired into the application, ahead of the controller")
                .containsEntry("correlationId", "order-4471");
    }

    @Test
    @DisplayName("logs and stores one and the same generated id when the caller sent none")
    void sharesTheGeneratedId() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-logged-generated"))
                .andExpect(status().isAccepted());

        ILoggingEvent line = logged.list.getFirst();
        Object stored = rowOf("it-logged-generated").get("correlation_id");
        assertThat(fieldsOf(line)).containsEntry("correlationId", stored);
        assertThat(line.getMDCPropertyMap()).containsEntry("correlationId", (String) stored);
    }

    @Test
    @DisplayName("logs nothing for a refused request")
    void logsNothingWhenRefused() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("X-Correlation-Id", "order 4471"))
                .andExpect(status().isBadRequest());

        assertThat(logged.list).isEmpty();
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    @Test
    @DisplayName("accepts a notification and answers 202 with its identifier")
    void acceptsANotification() throws Exception {
        MvcResult result =
                mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-accept"))
                        .andExpect(status().isAccepted())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.id").isNotEmpty())
                        .andReturn();

        String id = idOf(result);
        Map<String, Object> row = rowOf("it-accept");

        assertThat(row.get("id")).hasToString(id);
        assertThat(row.get("recipient")).isEqualTo("https://destination.test/hook");
        assertThat(row.get("channel")).isEqualTo("SERVICE");
        assertThat(row.get("subject")).isEqualTo("Your order shipped");
        assertThat(row.get("priority")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("stores it PENDING and already due, so the dispatcher needs no second signal")
    void storesItQueued() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-queued"))
                .andExpect(status().isAccepted());

        Map<String, Object> row = rowOf("it-queued");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("claimed_by")).isNull();
        assertThat(row.get("sent_at")).isNull();
        // Pins that the configured budget travels from configuration to column. Pinning the mapper
        // against a literal 3 is NotificationRepositoryIT.writesTheBudgetTheAggregateCarries' job.
        assertThat(row.get("max_attempts")).isEqualTo(3);
        // The insert is the enqueue: nothing else has to happen for this row to be picked up.
        assertThat((java.sql.Timestamp) row.get("next_attempt_at"))
                .isEqualTo((java.sql.Timestamp) row.get("created_at"));
    }

    @Test
    @DisplayName("answers a repeated idempotency key with the identifier of the first notification")
    void answersARepeatedKeyWithTheFirstIdentifier() throws Exception {
        String first =
                idOf(
                        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-repeat"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        String second =
                idOf(
                        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-repeat"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        assertThat(second).isEqualTo(first);
        // And the repeat created nothing: a client retrying a timed-out request must not cause a
        // second delivery.
        assertThat(countOf("it-repeat")).isEqualTo(1);
    }

    @Test
    @DisplayName("leaves the stored notification untouched when a repeat carries other headers")
    void aRepeatDoesNotDisturbTheStoredNotification() throws Exception {
        // A real retry rarely reproduces the first request exactly. Whatever arrives the second
        // time, the notification that already exists keeps the identity it was filed under,
        // since that is the one the first response referred to.
        mockMvc.perform(
                        submit(VALID_BODY)
                                .header("X-Correlation-Id", "order-first")
                                .header("Idempotency-Key", "it-repeat-headers"))
                .andExpect(status().isAccepted());

        mockMvc.perform(
                        submit(VALID_BODY)
                                .header("X-Correlation-Id", "order-second")
                                .header("Idempotency-Key", "it-repeat-headers"))
                .andExpect(status().isAccepted());

        assertThat(rowOf("it-repeat-headers").get("correlation_id")).isEqualTo("order-first");
        assertThat(countOf("it-repeat-headers")).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a repeated idempotency key carrying a different notification")
    void refusesARepeatedKeyWithADifferentPayload() throws Exception {
        String first =
                idOf(
                        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-conflict"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        mockMvc.perform(
                        submit(VALID_BODY.replace("Your order shipped", "Your order was cancelled"))
                                .header("Idempotency-Key", "it-conflict"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The Idempotency-Key was already used for a different notification"))
                .andExpect(jsonPath("$.idempotencyKey").value("it-conflict"))
                .andExpect(jsonPath("$.notificationId").value(first));

        assertThat(countOf("it-conflict")).isEqualTo(1);
        assertThat(rowOf("it-conflict").get("subject")).isEqualTo("Your order shipped");
    }

    @Test
    @DisplayName("reads a repeat whose metadata keys arrive in another order as the same payload")
    void metadataKeyOrderDoesNotMakeARepeatDifferent() throws Exception {
        String twoEntries =
                VALID_BODY.replace("\"orderId\": \"4471\"", "\"a\": \"1\", \"b\": \"2\"");
        String reordered =
                VALID_BODY.replace("\"orderId\": \"4471\"", "\"b\": \"2\", \"a\": \"1\"");

        String first =
                idOf(
                        mockMvc.perform(submit(twoEntries).header("Idempotency-Key", "it-order"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        String second =
                idOf(
                        mockMvc.perform(submit(reordered).header("Idempotency-Key", "it-order"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("accepts two notifications of one operation, each with its own key")
    void acceptsTwoNotificationsOfTheSameOperation() throws Exception {
        // Proven through the endpoint: a correlation id is shared by every notification of an
        // operation, an idempotency key never is. Crossing them would make the second come back
        // as a duplicate of the first and never be delivered.
        String toTheBuyer =
                idOf(
                        mockMvc.perform(
                                        submit(VALID_BODY)
                                                .header("X-Correlation-Id", "order-4471")
                                                .header("Idempotency-Key", "it-buyer"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        String toTheWarehouse =
                idOf(
                        mockMvc.perform(
                                        submit(VALID_BODY)
                                                .header("X-Correlation-Id", "order-4471")
                                                .header("Idempotency-Key", "it-warehouse"))
                                .andExpect(status().isAccepted())
                                .andReturn());

        assertThat(toTheBuyer).isNotEqualTo(toTheWarehouse);
        assertThat(rowOf("it-buyer").get("correlation_id")).isEqualTo("order-4471");
        assertThat(rowOf("it-warehouse").get("correlation_id")).isEqualTo("order-4471");
    }

    @Test
    @DisplayName("accepts a notification with no idempotency key at all")
    void acceptsANotificationWithoutAKey() throws Exception {
        mockMvc.perform(submit(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @DisplayName("files a notification under a generated correlation id when the caller sends none")
    void generatesACorrelationId() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-generated"))
                .andExpect(status().isAccepted());

        // The column is NOT NULL, so something has to be written, and it has to be a real
        // identifier rather than a placeholder: the grouping is worthless with holes in it.
        String generated = (String) rowOf("it-generated").get("correlation_id");
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    @DisplayName("accepts a subject of 512 emoji, which the column measures as 512 characters")
    void acceptsASubjectOfEmojiAtTheColumnWidth() throws Exception {
        // Anything on this path that counted UTF-16 units instead would read 512 emoji as 1024
        // and turn a legal subject away. The domain already asserts this about itself, but the
        // endpoint is the only way in.
        String atTheLimit = "🚀".repeat(512);

        mockMvc.perform(
                        submit(VALID_BODY.replace("Your order shipped", atTheLimit))
                                .header("Idempotency-Key", "it-emoji"))
                .andExpect(status().isAccepted());

        assertThat(rowOf("it-emoji").get("subject")).isEqualTo(atTheLimit);
    }

    @Test
    @DisplayName("accepts a recipient and an idempotency key exactly at their column widths")
    void acceptsValuesExactlyAtTheirLimits() throws Exception {
        // The rejecting side is covered below. Both sides are needed: a limit written with the
        // comparison one off turns a legal value away, and no test that only sends oversized
        // input can ever notice.
        String recipientAtTheLimit =
                "https://destination.test/"
                        + "x".repeat(2048 - "https://destination.test/".length());

        mockMvc.perform(
                        submit(
                                        VALID_BODY.replace(
                                                "https://destination.test/hook",
                                                recipientAtTheLimit))
                                .header("Idempotency-Key", "k".repeat(255)))
                .andExpect(status().isAccepted());

        assertThat(rowOf("k".repeat(255)).get("recipient")).isEqualTo(recipientAtTheLimit);
    }

    @Test
    @DisplayName("refuses a body whose required fields are missing, naming each one")
    void refusesAnIncompleteBody() throws Exception {
        mockMvc.perform(
                        submit(
                                """
                        {"recipient": "  ", "subject": "", "metadata": {}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.recipient").value("must not be blank"))
                .andExpect(jsonPath("$.errors.subject").value("must not be blank"))
                // Absent, so the answer is that it is missing. Which channels may be sent is a
                // different question, and it is answered where the caller actually asked one.
                .andExpect(jsonPath("$.errors.channel").value("must be present"))
                .andExpect(jsonPath("$.errors.priority").value("must be present"))
                .andExpect(jsonPath("$.errors.body").exists());
    }

    @Test
    @DisplayName("names every offending field at once, which is what the README shows")
    void namesEveryOffendingFieldAtOnce() throws Exception {
        // The exact body printed in the README's API section. A reader who copies it has to get
        // what the page shows -- and the page claims a 400 names every field, not the first.
        mockMvc.perform(
                        submit(
                                """
                        {"recipient": "  ", "channel": "SERVICE"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("One or more fields of the request body are invalid"))
                .andExpect(jsonPath("$.errors.recipient").value("must not be blank"))
                .andExpect(jsonPath("$.errors.subject").value("must not be blank"))
                .andExpect(
                        jsonPath("$.errors.body").value("must be present, though it may be empty"))
                .andExpect(jsonPath("$.errors.priority").value("must be present"))
                .andExpect(jsonPath("$.errors.channel").doesNotExist());
    }

    @Test
    @DisplayName("refuses a channel the service does not have, and says which it does")
    void refusesAnUnknownChannel() throws Exception {
        // Jackson rejects this before validation runs, so without a handler the caller would be
        // told only that the body was unreadable. The list offered is what this deployment can
        // deliver, not what the type can parse -- EMAIL has no adapter.
        mockMvc.perform(submit(VALID_BODY.replace("\"SERVICE\"", "\"SMS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.channel").value("must be one of LOG, SERVICE"));
    }

    @Test
    @DisplayName("refuses a recipient longer than the column that has to hold it")
    void refusesAnOversizedRecipient() throws Exception {
        mockMvc.perform(
                        submit(
                                VALID_BODY.replace(
                                        "https://destination.test/hook",
                                        "https://destination.test/" + "x".repeat(2048))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipient").value("must not exceed 2048 characters"));
    }

    @Test
    @DisplayName("names the priorities a caller may send, not just that the value was wrong")
    void refusesAnUnknownPriority() throws Exception {
        // Keeps the generic enum branch honest: priority has no deployment-dependent subset, so
        // its constants are the answer. Without this, that branch could collapse to "is not of
        // the expected type" with the suite still green.
        mockMvc.perform(submit(VALID_BODY.replace("\"HIGH\"", "\"URGENT\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.priority").value("must be one of LOW, MEDIUM, HIGH"));
    }

    @Test
    @DisplayName("refuses a channel this deployment has no adapter for, and writes no row")
    void refusesAnUndeliverableChannel() throws Exception {
        // EMAIL is a legal value of the type and of the column's check constraint, and nothing
        // wired into this deployment can send it. Accepting it would answer 202 and leave a row
        // failing every attempt until its budget ran out.
        mockMvc.perform(
                        submit(VALID_BODY.replace("\"SERVICE\"", "\"EMAIL\""))
                                .header("Idempotency-Key", "it-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.channel").value("must be one of LOG, SERVICE"));

        assertThat(countOf("it-email"))
                .as("a notification nothing can deliver must not be stored")
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ftp://destination.test/hook",
                "http://user:pw@destination.test/hook",
                "destination.test/hook"
            })
    @DisplayName("refuses a SERVICE recipient that is not an http URL without credentials")
    void refusesAServiceRecipientOfTheWrongForm(String recipient) throws Exception {
        mockMvc.perform(
                        submit(VALID_BODY.replace("https://destination.test/hook", recipient))
                                .header("Idempotency-Key", "it-form"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.recipient")
                                .value(
                                        "must be an absolute http or https URL without credentials"));

        assertThat(countOf("it-form")).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1/hook",
                "http://[::1]/hook",
                "http://10.0.0.5/hook",
                "http://169.254.169.254/latest/meta-data"
            })
    @DisplayName("refuses a SERVICE recipient that names a private or local address")
    void refusesAPrivateAddress(String recipient) throws Exception {
        mockMvc.perform(
                        submit(VALID_BODY.replace("https://destination.test/hook", recipient))
                                .header("Idempotency-Key", "it-private"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.recipient")
                                .value("must not name a private or local address"));

        assertThat(countOf("it-private")).isZero();
    }

    @Test
    @DisplayName("refuses a SERVICE recipient that names localhost")
    void refusesLocalhost() throws Exception {
        mockMvc.perform(
                        submit(VALID_BODY.replace("destination.test", "localhost"))
                                .header("Idempotency-Key", "it-localhost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recipient").value("must not name a local address"));

        assertThat(countOf("it-localhost")).isZero();
    }

    @Test
    @DisplayName("accepts a public name without resolving it, so an unresolvable host is still 202")
    void acceptsAnUnresolvablePublicName() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "it-public"))
                .andExpect(status().isAccepted());

        assertThat(countOf("it-public")).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a body wider than the column that has to hold it")
    void refusesAnOversizedBody() throws Exception {
        // The endpoint is the only way in, so a limit the domain and the column agree on is worth
        // nothing here unless this door enforces it too. Measured against the running service
        // before this test existed: a body of five megabytes was accepted and stored whole.
        mockMvc.perform(submit(VALID_BODY.replace("It is on its way.", "x".repeat(16385))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.body").value("must not exceed 16384 characters"));
    }

    @Test
    @DisplayName("refuses more metadata entries than the schema is willing to carry")
    void refusesTooManyMetadataEntries() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            entries.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }

        mockMvc.perform(submit(bodyWithMetadata(entries.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.metadata").value("must not exceed 32 entries"));
    }

    @Test
    @DisplayName("names the metadata entry whose value is too long, not merely the map")
    void namesTheOffendingMetadataEntry() throws Exception {
        // A caller carrying thirty-two entries cannot act on "metadata is invalid". It can act on
        // the key, which is the one piece of its own text worth sending back.
        mockMvc.perform(submit(bodyWithMetadata("\"orderId\":\"" + "v".repeat(2049) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.['metadata[orderId]']")
                                .value("must not exceed 2048 characters"));
    }

    @Test
    @DisplayName("refuses a metadata key wider than an identifier ever needs to be")
    void refusesAnOversizedMetadataKey() throws Exception {
        String key = "k".repeat(129);

        mockMvc.perform(submit(bodyWithMetadata("\"" + key + "\":\"v\"")))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.['metadata[" + key + "]']")
                                .value("must not exceed 128 characters"));
    }

    @Test
    @DisplayName("refuses a NUL character in the subject instead of failing at the database")
    void refusesANulCharacterInTheSubject() throws Exception {
        mockMvc.perform(
                        submit(
                                VALID_BODY.replace(
                                        "Your order shipped", "Your order\\u0000shipped")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subject").value("must not contain a NUL character"));
    }

    @Test
    @DisplayName("refuses a NUL character in a metadata value, naming its key")
    void refusesANulCharacterInAMetadataValue() throws Exception {
        mockMvc.perform(submit(bodyWithMetadata("\"orderId\":\"44\\u00007\"")))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.['metadata[orderId]']")
                                .value("must not contain a NUL character"));
    }

    @Test
    @DisplayName("refuses a blank metadata key, since a key is an identifier")
    void refusesABlankMetadataKey() throws Exception {
        mockMvc.perform(submit(bodyWithMetadata("\"  \":\"v\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.['metadata[  ]']").value("must not be blank"));
    }

    @Test
    @DisplayName("refuses a correlation id outside the character set it is allowed")
    void refusesAMalformedCorrelationId() throws Exception {
        // The value is written into a structured log line on every attempt, hence the narrow
        // set. A newline cannot travel in an HTTP header at all, and Spring Security's firewall
        // rejects it before any controller sees it; a space can arrive and must still be refused.
        mockMvc.perform(submit(VALID_BODY).header("X-Correlation-Id", "order 4471"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.errors.['X-Correlation-Id']")
                                .value(
                                        "must be 1 to 128 "
                                                + "characters, and may contain only letters, digits and the characters "
                                                + ". _ : -"));
    }

    @Test
    @DisplayName("names every header it refused, not only the first one it looked at")
    void refusesBothBadHeadersAtOnce() throws Exception {
        // The body path names every invalid field, and the README makes that promise in order to
        // spare a caller a second round trip. Checking the headers one at a time and stopping at
        // the first would quietly hold the same caller to a worse standard on the same request.
        mockMvc.perform(
                        submit(VALID_BODY)
                                .header("X-Correlation-Id", "order 4471")
                                .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.['X-Correlation-Id']").exists())
                .andExpect(jsonPath("$.errors.['Idempotency-Key']").exists());
    }

    @Test
    @DisplayName("refuses a blank idempotency key rather than reading it as absent")
    void refusesABlankIdempotencyKey() throws Exception {
        // The empty string is not null, so the partial unique index would store it as a real key
        // and every caller making the same mistake would collide with all the others.
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.['Idempotency-Key']")
                                .value(
                                        "must not be blank; omit the header rather than sending it empty"));
    }

    @Test
    @DisplayName("refuses an idempotency key longer than the column that has to hold it")
    void refusesAnOversizedIdempotencyKey() throws Exception {
        mockMvc.perform(submit(VALID_BODY).header("Idempotency-Key", "k".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.errors.['Idempotency-Key']")
                                .value("must not exceed 255 characters"));
    }

    @Test
    @DisplayName("answers the wrong method and the wrong media type in the documented shape")
    void refusesTheWrongMethodAndMediaType() throws Exception {
        // Both statuses are published in the README's error table, and both come from extending
        // Spring's ResponseEntityExceptionHandler rather than from anything written here. Dropping
        // that one clause would turn each of them into a 500 with nothing else going red.
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, CREDENTIAL))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(
                        post(PATH)
                                .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                                .contentType(MediaType.TEXT_PLAIN)
                                .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("refuses the notification without a credential, and writes no row")
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY)
                                .header("Idempotency-Key", "it-anonymous"))
                .andExpect(status().isUnauthorized());

        assertThat(countOf("it-anonymous")).isZero();
    }

    @Test
    @DisplayName("answers a refusal in the filter chain and a rejection in the controller alike")
    void bothEntryPointsAgreeOnTheShape() throws Exception {
        // The README promises RFC 9457 for both, and only this comparison keeps that promise
        // honest.
        MvcResult refused =
                mockMvc.perform(
                                post(PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(VALID_BODY))
                        .andExpect(status().isUnauthorized())
                        .andReturn();

        MvcResult rejected =
                mockMvc.perform(submit("{}")).andExpect(status().isBadRequest()).andReturn();

        for (MvcResult result : new MvcResult[] {refused, rejected}) {
            assertThat(result.getResponse().getContentType())
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());

            // Compared exactly, not by containment: a subset check passed while the 401 quietly
            // omitted "instance," which Spring fills in on the way out of the dispatcher
            // servlet -- and a refusal in the filter chain never reaches it.
            Set<String> members = new TreeSet<>();
            problem.fieldNames().forEachRemaining(members::add);
            // errors is an extension member of RFC 9457, present only when there is a list of
            // offending fields or headers to carry. The members below are the standard ones, and
            // those are what the two producers have to agree on.
            members.remove("errors");
            assertThat(members).containsExactly("detail", "instance", "status", "title", "type");
            assertThat(problem.get("status").asInt()).isEqualTo(result.getResponse().getStatus());
            assertThat(problem.get("detail").asText()).isNotBlank();
        }
    }

    /** The valid body, with its metadata object replaced by the given entries verbatim. */
    private static String bodyWithMetadata(String entries) {
        return VALID_BODY.replace("\"orderId\": \"4471\"", entries);
    }

    private static MockHttpServletRequestBuilder submit(String body) {
        return post(PATH)
                .header(HttpHeaders.AUTHORIZATION, CREDENTIAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String idOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Object> rowOf(String idempotencyKey) {
        return jdbc.queryForMap(
                "SELECT * FROM notification WHERE idempotency_key = ?", idempotencyKey);
    }

    private Integer countOf(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE idempotency_key = ?",
                Integer.class,
                idempotencyKey);
    }
}
