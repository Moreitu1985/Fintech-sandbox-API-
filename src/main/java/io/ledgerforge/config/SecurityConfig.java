package io.ledgerforge.config;

import io.ledgerforge.common.error.ProblemWriter;
import io.ledgerforge.security.AdminTokenFilter;
import io.ledgerforge.security.ApiKeyAuthFilter;
import io.ledgerforge.security.RateLimitFilter;
import io.ledgerforge.tenant.TenantService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                                            ProblemWriter problems) throws Exception {
        // Filters are created here, not as @Components, so they run only inside the security chain.
        var apiKeyFilter = new ApiKeyAuthFilter(tenants);
        var adminFilter = new AdminTokenFilter(props.adminToken());
        var rateLimitFilter = new RateLimitFilter(props.rateLimit(), problems);

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
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Only has a handler when the demo profile enables self-signup; otherwise 404.
                        .requestMatchers(HttpMethod.POST, "/sandbox/signup").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v1/**").hasRole("TENANT")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setHeader("WWW-Authenticate", "Bearer");
                            problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                                    "Missing or invalid credentials.");
                        })
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "These credentials cannot access this resource.")))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminFilter, ApiKeyAuthFilter.class)
                .addFilterAfter(rateLimitFilter, AdminTokenFilter.class);

        return http.build();
    }
}
