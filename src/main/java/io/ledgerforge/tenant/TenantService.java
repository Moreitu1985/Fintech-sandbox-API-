package io.ledgerforge.tenant;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.Hashing;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.security.ApiKeys;
import io.ledgerforge.security.TenantPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;
    private final AuditService audit;

    public TenantService(TenantRepository tenants, ApiKeyRepository apiKeys, AuditService audit) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.audit = audit;
    }

    public record IssuedKey(UUID keyId, String plaintext) {
    }

    public record CreatedTenant(Tenant tenant, IssuedKey key) {
    }

    @Transactional
    public CreatedTenant createTenant(String name) {
        Tenant tenant = tenants.save(new Tenant(name));
        audit.append(tenant.getId(), "TENANT_CREATED", "name=" + name);
        return new CreatedTenant(tenant, issue(tenant.getId()));
    }

    @Transactional
    public IssuedKey issueKey(UUID tenantId) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant")); // tenant first, always
        return issue(tenantId);
    }

    @Transactional
    public void revokeKey(UUID keyId) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() -> ApiException.notFound("API key"));
        if (key.isActive()) {
            key.revoke();
            audit.append(key.getTenantId(), "API_KEY_REVOKED", "keyId=" + key.getId());
        }
    }

    /** Resolves a presented key to its tenant. Unknown, malformed and revoked keys all look the same. */
    @Transactional(readOnly = true)
    public Optional<TenantPrincipal> authenticate(String presentedKey) {
        return ApiKeys.parse(presentedKey)
                .flatMap(parsed -> apiKeys.findByKeyPrefix(parsed.lookupPrefix())
                        .filter(ApiKey::isActive)
                        .filter(k -> Hashing.constantTimeEquals(k.getKeyHash(), ApiKeys.hash(parsed.plaintext())))
                        .map(k -> new TenantPrincipal(k.getTenantId(), k.getId())));
    }

    private IssuedKey issue(UUID tenantId) {
        ApiKeys.Generated generated = ApiKeys.generate();
        ApiKey key = apiKeys.save(new ApiKey(tenantId, generated.lookupPrefix(), generated.hash()));
        audit.append(tenantId, "API_KEY_ISSUED", "keyId=" + key.getId() + ";prefix=" + generated.lookupPrefix());
        return new IssuedKey(key.getId(), generated.plaintext());
    }
}
