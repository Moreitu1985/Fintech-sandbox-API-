package io.ledgerforge.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Platform operator endpoints, protected by the X-Admin-Token header. */
@RestController
@RequestMapping("/admin")
@SecurityRequirement(name = "adminToken")
public class AdminController {

    private static final String KEY_WARNING = "Store this key now. It is shown once and cannot be recovered.";

    private final TenantService tenants;

    public AdminController(TenantService tenants) {
        this.tenants = tenants;
    }

    public record CreateTenantRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
            String name) {
    }

    public record ApiKeyIssued(UUID keyId, String apiKey, String warning) {
    }

    public record TenantCreated(UUID tenantId, String name, ApiKeyIssued apiKey) {
    }

    @Operation(summary = "Create a tenant and its first API key")
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantCreated createTenant(@Valid @RequestBody CreateTenantRequest request) {
        var created = tenants.createTenant(request.name().strip());
        var key = created.key();
        return new TenantCreated(created.tenant().getId(), created.tenant().getName(),
                new ApiKeyIssued(key.keyId(), key.plaintext(), KEY_WARNING));
    }

    @Operation(summary = "Issue an additional API key (for rotation)")
    @PostMapping("/tenants/{tenantId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyIssued issueKey(@PathVariable UUID tenantId) {
        var key = tenants.issueKey(tenantId);
        return new ApiKeyIssued(key.keyId(), key.plaintext(), KEY_WARNING);
    }

    @Operation(summary = "Revoke an API key immediately")
    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeKey(@PathVariable UUID keyId) {
        tenants.revokeKey(keyId);
    }
}
