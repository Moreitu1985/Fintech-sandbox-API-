package io.ledgerforge.security;

import io.ledgerforge.oauth.Scopes;
import io.ledgerforge.tenant.TenantService;
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
 * Authenticates "Authorization: Bearer lf_test_..." headers. A bad key simply leaves the request
 * unauthenticated; the authorization rules then answer 401 without saying why.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    /** An API key used directly carries every scope (OAuth tokens can carry fewer). */
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_TENANT"),
            new SimpleGrantedAuthority(Scopes.authority(Scopes.LEDGER_READ)),
            new SimpleGrantedAuthority(Scopes.authority(Scopes.LEDGER_WRITE)),
            new SimpleGrantedAuthority(Scopes.authority(Scopes.ASSISTANT)));

    private final TenantService tenants;

    public ApiKeyAuthFilter(TenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            tenants.authenticate(header.substring(BEARER.length()).trim()).ifPresent(principal -> {
                var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        AUTHORITIES);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
