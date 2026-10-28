package io.ledgerforge.sandbox;

import io.ledgerforge.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Self-serve sandbox sign-up for the public demo: anyone can create an isolated tenant and get a
 * test API key without the admin token. Only exists when ledgerforge.sandbox.self-signup=true
 * (the "demo" profile); it is never registered in dev or prod. Abuse is bounded by the per-IP
 * rate limit, and the demo database is in memory, so it resets on every restart.
 */
@RestController
@ConditionalOnProperty(prefix = "ledgerforge.sandbox", name = "self-signup", havingValue = "true")
public class SandboxSignupController {

    private final TenantService tenants;

    public SandboxSignupController(TenantService tenants) {
        this.tenants = tenants;
    }

    public record SignupRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
            String name) {
    }

    public record SignupResponse(UUID tenantId, String name, String apiKey, String note) {
    }

    @Operation(summary = "Create a sandbox tenant and get a test API key (demo only)",
            description = "Use the returned key as 'Authorization: Bearer <apiKey>'. Demo data resets when the server restarts.")
    @SecurityRequirements
    @PostMapping("/sandbox/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        var created = tenants.createTenant(request.name().strip());
        return new SignupResponse(created.tenant().getId(), created.tenant().getName(), created.key().plaintext(),
                "Sandbox only: simulated money. The key is shown once; demo data resets on restart.");
    }
}
