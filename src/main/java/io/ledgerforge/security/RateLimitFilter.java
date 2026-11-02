package io.ledgerforge.security;

import io.ledgerforge.common.LedgerMetrics;
import io.ledgerforge.common.error.ProblemWriter;
import io.ledgerforge.config.LedgerForgeProperties;
import io.ledgerforge.security.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Token-bucket rate limiting per API key, or per client IP for unauthenticated traffic (which slows
 * down key guessing). The buckets live in memory or in Redis (shared by all instances), see
 * {@link io.ledgerforge.security.ratelimit.RateLimiterConfig}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final LedgerForgeProperties.RateLimit config;
    private final RateLimiter limiter;
    private final ProblemWriter problems;
    private final LedgerMetrics metrics;

    public RateLimitFilter(LedgerForgeProperties.RateLimit config, RateLimiter limiter, ProblemWriter problems,
                           LedgerMetrics metrics) {
        this.config = config;
        this.limiter = limiter;
        this.problems = problems;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = clientKey(request);
        RateLimiter.Decision decision = limiter.tryAcquire(key, config.capacity(), config.refillPerSecond());
        if (!decision.allowed()) {
            metrics.rateLimited(key.startsWith("key:") ? "api_key" : "ip");
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Slow down.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantPrincipal p) {
            return "key:" + p.keyId();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
