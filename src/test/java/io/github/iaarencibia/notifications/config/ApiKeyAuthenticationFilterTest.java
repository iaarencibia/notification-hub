package io.github.iaarencibia.notifications.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Reads the parser's answer directly -- the one thing a test through the filter chain cannot do.
 * From outside, "no credential" and "credential found but wrong" are the same {@code 401}, so an
 * end-to-end test cannot fail for a parsing regression; here the outcome is the authentication
 * itself.
 */
class ApiKeyAuthenticationFilterTest {

    private static final String KEY = "the-configured-key";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @DisplayName("reads no credential at all from a header it cannot parse")
    @ValueSource(
            strings = {
                "",
                "   ",
                "ApiK",
                "ApiKey",
                "ApiKey ",
                "ApiKey   ",
                "ApiKey" + KEY,
                "ApiKeyV2 " + KEY,
                "Bearer " + KEY
            })
    void readsNoCredentialFromAnUnparseableHeader(String authorization) throws Exception {
        // The case that earns its place: the scheme run straight into the key with no separator.
        // Drop that guard and this is the one value that parses into the key itself and lets the
        // caller in; every other value here stays unauthenticated regardless.
        assertThat(authenticationAfter(KEY, authorization))
                .as("the request must be left unauthenticated, not merely unmatched")
                .isNull();
    }

    @ParameterizedTest
    @DisplayName("reads the credential through every separator form RFC 9110 allows")
    @ValueSource(
            strings = {
                "ApiKey " + KEY,
                "ApiKey  " + KEY,
                "ApiKey " + KEY + "  ",
                "ApiKey   " + KEY + " ",
                "apikey " + KEY
            })
    void readsTheCredentialThroughEverySeparatorForm(String authorization) throws Exception {
        assertThat(authenticationAfter(KEY, authorization)).isNotNull();
    }

    @Test
    @DisplayName("leaves a request carrying the wrong key unauthenticated")
    void leavesTheWrongKeyUnauthenticated() throws Exception {
        assertThat(authenticationAfter(KEY, "ApiKey not-" + KEY)).isNull();
    }

    @Test
    @DisplayName("still authenticates when the configured key was typed with stray whitespace")
    void toleratesWhitespaceAroundTheConfiguredKey() throws Exception {
        // A space cannot travel inside a token68, so one here is only an invisible typo in
        // configuration. Trimming just the caller's side would reject every request forever.
        assertThat(authenticationAfter("  " + KEY + "  ", "ApiKey " + KEY))
                .as("a stray space in configuration must not lock the service out of itself")
                .isNotNull();
    }

    @Test
    @DisplayName("saves the recognised caller where a later dispatch of the same request finds it")
    void savesTheContextForALaterDispatch() throws Exception {
        // The holder is cleared when the REQUEST pass ends, and this filter does not run again on
        // the ERROR or ASYNC dispatch that can follow. Without the save, an authenticated caller
        // whose request takes either path comes back anonymous.
        RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey " + KEY);

        new ApiKeyAuthenticationFilter(KEY, repository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(repository.loadDeferredContext(request).get().getAuthentication())
                .as("the context has to outlive the holder, which the request pass clears")
                .isNotNull();
    }

    @Test
    @DisplayName("saves nothing for a caller it did not recognise")
    void savesNothingForAnUnrecognisedCaller() throws Exception {
        // Carrying the context across a dispatch must not become a way in for someone who never
        // presented a credential the service accepted.
        RequestAttributeSecurityContextRepository repository =
                new RequestAttributeSecurityContextRepository();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, "ApiKey not-" + KEY);

        new ApiKeyAuthenticationFilter(KEY, repository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(repository.containsContext(request)).isFalse();
    }

    /** The authentication the filter left behind, or {@code null} if it recognised nobody. */
    private static Authentication authenticationAfter(String configuredKey, String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);

        new ApiKeyAuthenticationFilter(
                        configuredKey, new RequestAttributeSecurityContextRepository())
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        return SecurityContextHolder.getContext().getAuthentication();
    }
}
