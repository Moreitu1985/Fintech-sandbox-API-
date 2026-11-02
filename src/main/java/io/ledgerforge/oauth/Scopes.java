package io.ledgerforge.oauth;

import java.util.List;

/** OAuth2 scopes. An API key used directly has all of them; a token carries only what it was issued with. */
public final class Scopes {

    public static final String LEDGER_READ = "ledger:read";
    public static final String LEDGER_WRITE = "ledger:write";
    public static final String ASSISTANT = "assistant";
    public static final List<String> ALL = List.of(LEDGER_READ, LEDGER_WRITE, ASSISTANT);

    private Scopes() {
    }

    public static String authority(String scope) {
        return "SCOPE_" + scope;
    }
}
