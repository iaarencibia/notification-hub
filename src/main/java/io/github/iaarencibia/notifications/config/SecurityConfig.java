package io.github.iaarencibia.notifications.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The single place that decides who may reach what.
 *
 * <p>Why the mechanism is a shared key, and what that costs, is argued in the README. Here is only
 * the shape of it.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            NotificationsProperties properties,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        // Written by the filter, read back by the chain on every dispatch of the same request.
        // Both sides find it because the attribute is named after the class, not the instance.
        SecurityContextRepository contextRepository =
                new RequestAttributeSecurityContextRepository();

        return http.authorizeHttpRequests(
                        requests -> {
                            // Matched through the endpoint, not a literal path, so moving the
                            // actuator base path cannot silently close the probe the healthcheck
                            // polls.
                            requests.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                                    .permitAll();
                            // Left authenticated when the documentation is off, so these paths
                            // answer 401 like any other instead of revealing they exist.
                            if (apiDocsEnabled) {
                                requests.requestMatchers(
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll();
                            }
                            // Deny by default, the container's own /error dispatch included; what
                            // that costs is in the README's trade-offs.
                            requests.anyRequest().authenticated();
                        })
                // Placed before AnonymousAuthenticationFilter, not just before the authorization
                // filter, so a recognised caller never has an anonymous token written over it.
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(
                                properties.security().apiKey(), contextRepository),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(
                                        new ApiKeyAuthenticationEntryPoint(exceptionResolver)))
                // The credential travels on every request, so there is no state to keep -- and no
                // rejected request stashed in a session awaiting a login that never comes.
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Nothing is attached to a request automatically here, so there is nothing to
                // forge; left on, it would reject every POST for want of an unobtainable token.
                .csrf(CsrfConfigurer::disable)
                // Without a session there is nothing to log out of, and /logout would only add a
                // route that redirects to a login page this service has not got.
                .logout(LogoutConfigurer::disable)
                .build();
    }
}
