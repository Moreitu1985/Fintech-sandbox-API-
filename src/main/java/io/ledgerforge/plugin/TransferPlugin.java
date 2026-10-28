package io.ledgerforge.plugin;

/**
 * The plugin contract. Any Spring bean implementing this is discovered automatically;
 * the ledger core never changes when a plugin is added. Plugins run in {@code @Order} order.
 *
 * <ul>
 *   <li>{@link #beforeLocking} runs inside the transfer's transaction but before any row lock is taken.
 *       Use it for slow work such as remote calls (fraud scoring): it sees committed, unlocked data, and
 *       never stalls other payments. Throw to refuse the transfer.</li>
 *   <li>{@link #beforeTransfer} runs with both accounts locked, just before money moves. Keep it fast
 *       and local. Throw {@link PluginRejectedException} to block the transfer.</li>
 *   <li>{@link #afterCommit} runs only once the transfer is durably committed (notifications,
 *       webhooks, analytics). Failures there are logged and never undo the transfer.</li>
 * </ul>
 */
public interface TransferPlugin {

    String name();

    default String description() {
        return "";
    }

    default void beforeLocking(TransferContext context) {
    }

    default void beforeTransfer(TransferContext context) {
    }

    default void afterCommit(TransferContext context) {
    }
}
