package io.ledgerforge.plugin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Example plugin: blocks any single transfer above a configured limit. Toggle it off in config. */
@Component
@ConditionalOnProperty(prefix = "ledgerforge.plugins.max-transfer", name = "enabled", havingValue = "true")
public class MaxTransferAmountPlugin implements TransferPlugin {

    private final long maxAmount;

    public MaxTransferAmountPlugin(@Value("${ledgerforge.plugins.max-transfer.amount}") long maxAmount) {
        this.maxAmount = maxAmount;
    }

    @Override
    public String name() {
        return "max-transfer-amount";
    }

    @Override
    public String description() {
        return "Rejects single transfers above " + maxAmount + " minor units";
    }

    @Override
    public void beforeTransfer(TransferContext context) {
        if (context.amount() > maxAmount) {
            throw new PluginRejectedException(name(), "amount exceeds the per-transfer limit of " + maxAmount);
        }
    }
}
