package io.ledgerforge.config;

import io.ledgerforge.common.error.ProblemWriter;
import io.ledgerforge.common.LedgerMetrics;
import io.ledgerforge.oauth.Scopes;
import io.ledgerforge.security.AdminTokenFilter;
import io.ledgerforge.security.ApiKeyAuthFilter;
import io.ledgerforge.security.MetricsTokenFilter;
import io.ledgerforge.security.RateLimitFilter;
import io.ledgerforge.security.ratelimit.RateLimiter;
import io.ledgerforge.tenant.TenantService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TenantService tenants, LedgerForgeProperties props,
                                            ProblemWriter problems, RateLimiter limiter, LedgerMetrics metrics,
                                            BearerTokenResolver jwtOnlyBearerTokenResolver,
                                            Converter<Jwt, AbstractAuthenticationToken> tenantJwtConverter)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> {
            res.setHeader("WWW-Authenticate", "Bearer");
            problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid credentials.");
        };
        // Filters are created here, not as @Components, so they run only inside the security chain.
        var apiKeyFilter = new ApiKeyAuthFilter(tenants);
        var adminFilter = new AdminTokenFilter(props.adminToken());
        var metricsFilter = new MetricsTokenFilter(props.metricsToken());
        var rateLimitFilter = new RateLimitFilter(props.rateLimit(), limiter, problems, metrics);

        http
                // Stateless token API: no cookies or sessions, so CSRF protection does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Read-only metrics token only: not tenant keys, not the admin token (least privilege).
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("METRICS")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Only has a handler when the demo profile enables self-signup; otherwise 404.
                        .requestMatchers(HttpMethod.POST, "/sandbox/signup").permitAll()
                        // OAuth 2.0: the token endpoint authenticates the client itself; the key set is public.
                        .requestMatchers(HttpMethod.POST, "/oauth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/oauth/jwks.json").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // Scopes: API keys hold all three; access tokens only what they were issued with.
                        // The assistant reads the ledger for you, so it needs ledger:read as well as assistant.
                        .requestMatchers("/v1/assistant/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority(Scopes.authority(Scopes.ASSISTANT)),
                                AuthorityAuthorizationManager.hasAuthority(Scopes.authority(Scopes.LEDGER_READ))))
                        .requestMatchers(HttpMethod.GET, "/v1/**").hasAuthority(Scopes.authority(Scopes.LEDGER_READ))
                        .requestMatchers(HttpMethod.HEAD, "/v1/**").hasAuthority(Scopes.authority(Scopes.LEDGER_READ))
                        .requestMatchers("/v1/**").hasAuthority(Scopes.authority(Scopes.LEDGER_WRITE))
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "These credentials cannot access this resource.")))
                // Signed, short-lived access tokens (JWT) from /oauth/token, alongside API keys.
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(jwtOnlyBearerTokenResolver)
                        .jwt(j -> j.jwtAuthenticationConverter(tenantJwtConverter))
                        .authenticationEntryPoint(unauthorized))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminFilter, ApiKeyAuthFilter.class)
                .addFilterAfter(metricsFilter, AdminTokenFilter.class)
                // After every way of authenticating, so token callers are limited per key, not per IP.
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
