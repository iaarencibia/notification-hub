package io.github.iaarencibia.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What the filter leaves in the MDC and on the request while the rest of the chain runs. */
class CorrelationIdFilterTest {

    private static final String PATH = "/api/v1/notifications";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void cleanTheMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("puts a valid caller-supplied id in the MDC and on the request")
    void usesTheCallersId() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "order-4471");
        RecordingChain seen = new RecordingChain();

        filter.doFilter(request, response, seen);

        assertThat(seen.mdc.get()).isEqualTo("order-4471");
        assertThat(seen.attribute.get()).isEqualTo(new CorrelationId("order-4471"));
    }

    @Test
    @DisplayName(
            "generates one when the header is absent, and shares that same id with the request")
    void generatesWhenAbsent() throws Exception {
        RecordingChain seen = new RecordingChain();

        filter.doFilter(request(), response, seen);

        assertThat(seen.attribute.get()).isInstanceOf(CorrelationId.class);
        assertThat(seen.mdc.get()).isEqualTo(seen.attribute.get().toString());
        assertThat(java.util.UUID.fromString(seen.mdc.get())).isNotNull();
    }

    @Test
    @DisplayName("leaves the MDC and the request alone when the header is invalid")
    void ignoresAnInvalidId() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "order 4471");
        RecordingChain seen = new RecordingChain();

        filter.doFilter(request, response, seen);

        assertThat(seen.mdc.get()).isNull();
        assertThat(seen.attribute.get()).isNull();
    }

    @Test
    @DisplayName("clears the MDC once the chain returns")
    void cleansUpAfterTheChain() throws Exception {
        filter.doFilter(request(), response, new RecordingChain());

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("clears the MDC when the chain throws")
    void cleansUpAfterAFailure() {
        FilterChain failing =
                (req, res) -> {
                    throw new IllegalStateException("boom");
                };

        assertThatThrownBy(() -> filter.doFilter(request(), response, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("applies to the API and not to the actuator")
    void appliesOnlyToTheApi() {
        assertThat(filter.shouldNotFilter(request("/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request(PATH))).isFalse();
    }

    private static MockHttpServletRequest request() {
        return request(PATH);
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        return request;
    }

    private static final class RecordingChain implements FilterChain {

        private final AtomicReference<String> mdc = new AtomicReference<>();
        private final AtomicReference<Object> attribute = new AtomicReference<>();

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            mdc.set(MDC.get("correlationId"));
            attribute.set(req.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
        }
    }
}
