package io.github.iaarencibia.notifications.adapter.in.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Counts each refused request to the intake endpoint by the reason it was refused. */
@Component
public final class IntakeRefusals {

    private static final String INTAKE_PATH = "/api/v1/notifications";

    public enum Reason {
        INVALID_BODY,
        UNREADABLE_BODY,
        INVALID_HEADER,
        UNSUPPORTED_MEDIA_TYPE,
        NOT_ACCEPTABLE,
        UNDELIVERABLE_CHANNEL,
        UNACCEPTABLE_RECIPIENT,
        IDEMPOTENCY_KEY_REUSED,
        UNAUTHENTICATED;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    IntakeRefusals(MeterRegistry registry) {
        this.registry = registry;
        for (Reason reason : Reason.values()) {
            counter(reason);
        }
    }

    /** Counts the refusal if the request is an intake request; any other request is ignored. */
    public void refused(HttpServletRequest request, Reason reason) {
        if (isIntake(request)) {
            counter(reason).increment();
        }
    }

    private static boolean isIntake(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return request.getDispatcherType() == DispatcherType.REQUEST
                && HttpMethod.POST.matches(request.getMethod())
                && INTAKE_PATH.equals(path);
    }

    private Counter counter(Reason reason) {
        return registry.counter("notifications.refused", "reason", reason.label());
    }
}
