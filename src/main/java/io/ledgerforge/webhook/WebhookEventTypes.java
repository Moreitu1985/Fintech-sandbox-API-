package io.ledgerforge.webhook;

import java.util.List;

/** Event types a tenant can subscribe to. */
public final class WebhookEventTypes {

    public static final String TRANSFER_POSTED = "transfer.posted";
    public static final String ACCOUNT_CREATED = "account.created";
    /** Sent only by POST /v1/webhooks/{id}/test, to the one endpoint being tested. */
    public static final String TEST = "webhook.test";

    public static final List<String> SUBSCRIBABLE = List.of(TRANSFER_POSTED, ACCOUNT_CREATED);

    private WebhookEventTypes() {
    }
}
