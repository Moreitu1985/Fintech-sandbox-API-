package io.ledgerforge.security;

import java.util.UUID;

/** The authenticated caller: which tenant, via which API key. */
public record TenantPrincipal(UUID tenantId, UUID keyId) {
}
