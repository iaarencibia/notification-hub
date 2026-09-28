package io.github.iaarencibia.notifications.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Writes the response for a request the filter chain refused, before it ever reached the dispatcher
 * servlet and any {@code @ControllerAdvice} the web adapter registers. Left to itself, the chain
 * falls back to a bodiless {@code 403} with no challenge.
 *
 * <p>The wording is the same whether the credential was absent or simply wrong, so a caller
 * guessing keys cannot learn from the response which half it got right.
 */
class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CHALLENGE =
            ApiKeyAuthenticationFilter.SCHEME + " realm=\"notification-service\"";

    private static final String DETAIL =
            "A valid credential using the "
                    + ApiKeyAuthenticationFilter.SCHEME
                    + " scheme is required in the Authorization header";

    private final ObjectMapper objectMapper;

    ApiKeyAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // RFC 9110 requires a 401 to carry a challenge; naming the scheme is also the only way a
        // caller learns what to send.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETAIL);
        // Set by hand: Spring normally fills `instance` in on the way out of the dispatcher
        // servlet, which a refusal in the filter chain never reaches.
        problem.setInstance(URI.create(request.getRequestURI()));

        this.objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
