package io.ledgerforge.security;

import io.ledgerforge.common.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Grants ROLE_METRICS for "Authorization: Bearer &lt;metrics token&gt;" on /actuator paths only.
 * A separate, read-only secret (least privilege): the scraper never holds the admin token, and the
 * metrics token can't create tenants or touch money. Uses the bearer scheme Prometheus supports natively.
 */
public class MetricsTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private final String token;

    public MetricsTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return token == null || !request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)
                && Hashing.constantTimeEquals(token, header.substring(BEARER.length()).trim())) {
            var auth = UsernamePasswordAuthenticationToken.authenticated("metrics-scraper", null,
                    List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
