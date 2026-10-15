package io.github.iaarencibia.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.adapter.in.web.IntakeRefusals.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** What a refused intake request becomes in the metrics. */
class IntakeRefusalsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IntakeRefusals refusals = new IntakeRefusals(registry);

    @ParameterizedTest
    @EnumSource(Reason.class)
    @DisplayName("exports every reason at zero before any request")
    void startsEveryReasonAtZero(Reason reason) {
        assertThat(refused(reason.label())).isZero();
    }

    @Test
    @DisplayName("labels each reason in snake_case")
    void labelsReasonsInSnakeCase() {
        assertThat(Reason.IDEMPOTENCY_KEY_REUSED.label()).isEqualTo("idempotency_key_reused");
        assertThat(Reason.UNAUTHENTICATED.label()).isEqualTo("unauthenticated");
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    @DisplayName("counts a refused intake request once, under its own reason")
    void countsAnIntakeRefusal(Reason reason) {
        refusals.refused(intake(), reason);

        assertThat(refused(reason.label())).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    @Test
    @DisplayName("counts a refusal under the application's context path")
    void countsUnderAContextPath() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/hub/api/v1/notifications");
        request.setContextPath("/hub");

        refusals.refused(request, Reason.INVALID_BODY);

        assertThat(refused("invalid_body")).isEqualTo(1);
    }

    @Test
    @DisplayName("does not count a refusal of the query endpoint")
    void ignoresTheQueryEndpoint() {
        refusals.refused(
                new MockHttpServletRequest(
                        "GET", "/api/v1/notifications/6f1c3e2a-4b5d-4e8f-9a0b-1c2d3e4f5a6b"),
                Reason.UNAUTHENTICATED);

        assertThat(total()).isZero();
    }

    @Test
    @DisplayName("does not count another method on the intake path")
    void ignoresAnotherMethodOnTheIntakePath() {
        refusals.refused(
                new MockHttpServletRequest("GET", "/api/v1/notifications"), Reason.UNAUTHENTICATED);

        assertThat(total()).isZero();
    }

    @Test
    @DisplayName("does not count a refusal of the actuator")
    void ignoresTheActuator() {
        refusals.refused(
                new MockHttpServletRequest("POST", "/actuator/prometheus"), Reason.UNAUTHENTICATED);

        assertThat(total()).isZero();
    }

    @Test
    @DisplayName("does not count the error dispatch of a request already counted")
    void ignoresTheErrorDispatch() {
        MockHttpServletRequest request = intake();
        request.setDispatcherType(DispatcherType.ERROR);

        refusals.refused(request, Reason.UNAUTHENTICATED);

        assertThat(total()).isZero();
    }

    private static MockHttpServletRequest intake() {
        return new MockHttpServletRequest("POST", "/api/v1/notifications");
    }

    private double refused(String reason) {
        return registry.get("notifications.refused").tag("reason", reason).counter().count();
    }

    private double total() {
        return registry.find("notifications.refused").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
