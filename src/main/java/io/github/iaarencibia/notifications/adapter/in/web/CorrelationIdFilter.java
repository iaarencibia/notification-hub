package io.github.iaarencibia.notifications.adapter.in.web;

import io.github.iaarencibia.notifications.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the request's correlation id available to every log line written while it is handled, and
 * to the controller, so both agree on one value.
 */
@Component
// Ahead of the security chain, so its 401 lines carry the id too.
@Order(Ordered.HIGHEST_PRECEDENCE)
final class CorrelationIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "correlationId";
    static final String REQUEST_ATTRIBUTE = "notifications.correlationId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CorrelationId correlationId =
                resolve(request.getHeader(NotificationController.CORRELATION_ID_HEADER));
        if (correlationId == null) {
            // An unvetted value never reaches a log line.
            chain.doFilter(request, response);
            return;
        }
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        MDC.put(MDC_KEY, correlationId.value());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static CorrelationId resolve(String header) {
        if (header == null) {
            return CorrelationId.generate();
        }
        try {
            return new CorrelationId(header);
        } catch (IllegalArgumentException rejected) {
            return null;
        }
    }
}
