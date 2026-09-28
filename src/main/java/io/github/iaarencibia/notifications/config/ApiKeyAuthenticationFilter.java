package io.github.iaarencibia.notifications.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Recognises the caller, and nothing else.
 *
 * <p>A request that arrives without a credential, or with the wrong one, is left unauthenticated
 * and allowed to continue: <strong>rejecting is not this filter's job</strong>. Which routes
 * require a caller is declared once, in {@link SecurityConfig}, so the access policy stays in one
 * place instead of a filter and a rule that could drift apart.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** The authentication scheme, as it is announced in the {@code WWW-Authenticate} challenge. */
    static final String SCHEME = "ApiKey";

    /**
     * Names the caller in the security context, which is what reaches the logs and the actuator.
     * There is a single credential, so there is nothing more specific to say.
     */
    private static final String PRINCIPAL = "api-client";

    private final byte[] expectedKey;
    private final SecurityContextRepository securityContextRepository;

    ApiKeyAuthenticationFilter(String apiKey, SecurityContextRepository securityContextRepository) {
        // Trimmed on both sides or neither. A token68 cannot carry a space, so one around the
        // configured value is only a typo -- trimming just the caller's side would lock the
        // service out of itself.
        this.expectedKey = apiKey.strip().getBytes(StandardCharsets.UTF_8);
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = presentedKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (presented != null && matches(presented)) {
            // No authority granted: access here is all or nothing, and an authority nobody
            // consults would be a permission model that does not exist.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new PreAuthenticatedAuthenticationToken(
                            PRINCIPAL, null, AuthorityUtils.NO_AUTHORITIES));
            SecurityContextHolder.setContext(context);

            // Saved as well as held: the holder clears when the REQUEST pass ends, and this
            // filter does not run on the ERROR dispatch that may follow. Without the save, a
            // caller whose request ends in sendError comes back anonymous.
            this.securityContextRepository.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    private static String presentedKey(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            // Case-insensitive: RFC 9110 allows `apikey` as well as `ApiKey`.
            return null;
        }

        // RFC 9110 puts 1*SP between the scheme and the credential, not exactly one, and the
        // field value may carry trailing whitespace -- both still well-formed.
        int start = SCHEME.length();
        while (start < authorization.length() && authorization.charAt(start) == ' ') {
            start++;
        }
        if (start == SCHEME.length()) {
            // Nothing separates the scheme name from what follows: either a different scheme
            // that merely begins with these characters (`ApiKeyV2 ...`), or this one with no
            // credential. Without this guard, `ApiKey<key>` would parse the key straight out.
            return null;
        }
        String credential = authorization.substring(start).strip();
        return credential.isEmpty() ? null : credential;
    }

    /**
     * Compares in constant time. {@code String.equals} returns as soon as two characters differ, so
     * its timing tells a caller how many leading characters it guessed. {@link
     * MessageDigest#isEqual} folds the length difference in and walks the whole presented array, so
     * the timing depends only on what the caller sent, never on the configured key.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), this.expectedKey);
    }
}
