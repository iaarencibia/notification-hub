package io.github.iaarencibia.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * What a caller actually receives, over real HTTP, from a real servlet container on a random port:
 * the statuses that separate "you may not" from "there is nothing there", and the one route that
 * answers everybody. A real container matters here because MockMvc performs no {@code /error}
 * redispatch of its own.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = IntegrationTestSupport.API_KEY_PROPERTY)
class ApiKeyErrorDispatchIT extends IntegrationTestSupport {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("answers an authenticated caller with the real status when the request errors")
    void authenticatedCallerSurvivesTheErrorDispatch() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/no-such-path", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode())
                .as("a credential the service accepted must not come back as 401 on an error")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("still refuses an unauthenticated caller on the same error path")
    void unauthenticatedCallerIsStillRefused() {
        ResponseEntity<String> response = restTemplate.getForEntity("/no-such-path", String.class);

        assertThat(response.getStatusCode())
                .as("carrying the context across the dispatch must not open a way in")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("tells an anonymous caller nothing that separates a real route from a missing one")
    void anonymousCallersCannotMapTheSurface() {
        ResponseEntity<String> real = restTemplate.getForEntity("/actuator/metrics", String.class);
        ResponseEntity<String> missing = restTemplate.getForEntity("/no-such-path", String.class);

        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getStatusCode())
                .as("a route that exists and one that does not must answer the same")
                .isEqualTo(real.getStatusCode());
    }

    @Test
    @DisplayName("answers the accurate status on the one route open to everyone")
    void thePermittedRouteAnswersAccurately() {
        // Nothing leaks here: this route is permitted and documented, so a 405 reveals nothing --
        // every route that is not permitted still fails earlier in the chain.
        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/actuator/health", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
