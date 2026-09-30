package io.github.iaarencibia.notifications.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Hands a request the filter chain refused, before it ever reached the dispatcher servlet, to the
 * exception handling of the web adapter, so the response is built in one place. Left to itself, the
 * chain falls back to a bodiless {@code 403} with no challenge.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver exceptionResolver;

    ApiKeyAuthenticationEntryPoint(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) {
        exceptionResolver.resolveException(request, response, null, authenticationException);
    }
}
