package io.ledgerforge.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.time.Duration;

/** OAuth 2.0 token endpoint (client credentials) and the public key set that verifies its tokens. */
@RestController
public class TokenController {

    private final TokenService tokens;
    private final Map<String, Object> publicJwks;

    public TokenController(TokenService tokens, RSAKey tokenSigningKey) {
        this.tokens = tokens;
        this.publicJwks = new JWKSet(tokenSigningKey.toPublicJWK()).toJSONObject(); // public parts only
    }

    @Operation(summary = "Exchange client credentials for a short-lived access token",
            description = "OAuth 2.0 client_credentials grant. Send parameters in the form body (never the URL). "
                    + "Authenticate with HTTP Basic (client_id = API key id, client_secret = API key) or with "
                    + "client_id/client_secret form fields, not both. Optional `scope`: any of "
                    + "ledger:read ledger:write assistant (default: all). Use the token as `Authorization: Bearer`.")
    @SecurityRequirements
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            // Anything in the URL ends up in access and proxy logs; an API key must never travel there.
            throw invalidRequest("Send parameters in the request body, not the URL");
        }
        String grantType = single(request, "grant_type");
        String scope = single(request, "scope");
        String clientId = single(request, "client_id");
        String clientSecret = single(request, "client_secret");
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            if (clientId != null || clientSecret != null) {
                throw invalidRequest("Use one client authentication method, not both");
            }
            String[] pair = decodeBasic(authorization.substring(6).trim());
            clientId = pair[0];
            clientSecret = pair[1];
        }
        var issued = tokens.issue(grantType, clientId, clientSecret, scope);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Map.of(
                        "access_token", issued.accessToken(),
                        "token_type", "Bearer",
                        "expires_in", issued.expiresIn(),
                        "scope", issued.scope()));
    }

    /** RFC 6749 3.2: a parameter must not appear more than once. */
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length > 1) {
            throw invalidRequest("Parameter '" + name + "' must not be repeated");
        }
        return values[0];
    }

    private static TokenService.OAuthError invalidRequest(String description) {
        return new TokenService.OAuthError(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request", description);
    }

    @Operation(summary = "Public keys for verifying access tokens (JWKS)")
    @SecurityRequirements
    @GetMapping(value = "/oauth/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(publicJwks);
    }

    @ExceptionHandler(TokenService.OAuthError.class)
    public ResponseEntity<Map<String, String>> oauthError(TokenService.OAuthError e) {
        var response = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
        if (e.status().value() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"ledgerforge\"");
        }
        if (e.status().value() == 429) {
            response.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return response.body(Map.of("error", e.error(), "error_description", e.getMessage()));
    }

    private static String[] decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException();
            }
            // RFC 6749 2.3.1: both parts are form-urlencoded before Basic encoding.
            return new String[]{
                    java.net.URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            throw new TokenService.OAuthError(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_client",
                    "Client authentication failed");
        }
    }
}
