package io.github.iaarencibia.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import io.github.iaarencibia.notifications.application.port.in.submit.SubmitNotificationUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** What an unhandled failure leaves in the HTTP server metrics. */
@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureObservability
@AutoConfigureMockMvc
class UnhandledFailureObservationIT extends IntegrationTestSupport {

    private static final String PATH = "/api/v1/notifications";

    @MockitoBean private SubmitNotificationUseCase intake;

    @Autowired private MockMvc mockMvc;

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("records the exception behind a 500 as the error of the request")
    void recordsTheUnhandledException() throws Exception {
        given(intake.submit(any())).willThrow(new IllegalStateException("boom"));

        mockMvc.perform(submit()).andExpect(status().isInternalServerError());

        assertThat(errorsOf("500")).containsExactly("IllegalStateException");
    }

    @Test
    @DisplayName("records no error for a refusal the service answers on purpose")
    void recordsNoErrorForARefusal() throws Exception {
        mockMvc.perform(
                        post(PATH)
                                .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(errorsOf("400")).containsOnly("none");
    }

    private Collection<String> errorsOf(String status) {
        return registry
                .find("http.server.requests")
                .tag("uri", PATH)
                .tag("status", status)
                .timers()
                .stream()
                .map(Timer::getId)
                .map(id -> id.getTag("error"))
                .toList();
    }

    private static RequestBuilder submit() {
        return post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {
                          "recipient": "http://example.test/hook",
                          "channel": "SERVICE",
                          "subject": "Order shipped",
                          "body": "Your order 1234 has shipped.",
                          "priority": "HIGH"
                        }
                        """);
    }
}
