-- Webhooks: tenants register HTTPS endpoints; events are written in the same transaction as the change
-- they describe (transactional outbox) and delivered afterwards, with retries, by the dispatcher.

CREATE TABLE webhook_endpoints (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID NOT NULL REFERENCES tenants (id),
    url                      VARCHAR(2048) NOT NULL,
    event_types              VARCHAR(400) NOT NULL,   -- comma-separated, e.g. transfer.posted,account.created
    description              VARCHAR(140),
    secret_ciphertext        VARCHAR(200) NOT NULL,   -- signing secret, AES-256-GCM encrypted; never stored in plain text
    enabled                  BOOLEAN NOT NULL,
    failed_deliveries_in_row INTEGER NOT NULL,
    created_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    disabled_at              TIMESTAMP(6) WITH TIME ZONE,
    deleted_at               TIMESTAMP(6) WITH TIME ZONE,
    version                  BIGINT NOT NULL DEFAULT 0   -- optimistic locking: no stale copy can overwrite a change
);
CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints (tenant_id);

CREATE TABLE webhook_events (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    event_type  VARCHAR(40) NOT NULL,
    payload     VARCHAR(100000) NOT NULL, -- the exact JSON body that is signed and sent
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE webhook_deliveries (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenants (id),
    endpoint_id       UUID NOT NULL REFERENCES webhook_endpoints (id),
    event_id          UUID NOT NULL REFERENCES webhook_events (id),
    status            VARCHAR(16) NOT NULL,     -- PENDING, SUCCEEDED, FAILED, CANCELLED
    attempts          INTEGER NOT NULL,
    next_attempt_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,  -- also the claim lease while a send is in flight
    last_attempt_at   TIMESTAMP(6) WITH TIME ZONE,
    last_status_code  INTEGER,
    last_error        VARCHAR(200),
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,   -- bumped by every claim and cancellation, so a sender whose
                                                   -- lease was taken over can't record over the new holder
    CONSTRAINT uq_webhook_delivery UNIQUE (endpoint_id, event_id)
);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (status, next_attempt_at);
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, created_at);
