package io.ledgerforge.security;

import io.ledgerforge.common.error.ProblemWriter;
import io.ledgerforge.config.LedgerForgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting per API key, or per client IP for unauthenticated traffic (which slows
 * down key guessing). In-memory, so limits are per instance; Phase 3 moves this to Redis.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final LedgerForgeProperties.RateLimit config;
    private final ProblemWriter problems;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(LedgerForgeProperties.RateLimit config, ProblemWriter problems) {
        this.config = config;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear(); // bound memory under a flood of distinct IPs
        }
        TokenBucket bucket = buckets.computeIfAbsent(clientKey(request),
                k -> new TokenBucket(config.capacity(), config.refillPerSecond()));
        if (!bucket.tryConsume()) {
            response.setHeader("Retry-After", Long.toString(bucket.secondsUntilNextToken()));
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
