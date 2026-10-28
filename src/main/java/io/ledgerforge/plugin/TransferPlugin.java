package io.ledgerforge.plugin;

/**
 * The plugin contract. Any Spring bean implementing this is discovered automatically;
 * the ledger core never changes when a plugin is added.
 *
 * <ul>
 *   <li>{@link #beforeTransfer} runs inside the transaction with both accounts locked.
 *       Throw {@link PluginRejectedException} to block the transfer.</li>
 *   <li>{@link #afterCommit} runs only once the transfer is durably committed (notifications,
 *       webhooks, analytics). Failures there are logged and never undo the transfer.</li>
 * </ul>
 */
public interface TransferPlugin {

    String name();

    default String description() {
        return "";
    }

    default void beforeTransfer(TransferContext context) {
    }

    default void afterCommit(TransferContext context) {
    }
}
