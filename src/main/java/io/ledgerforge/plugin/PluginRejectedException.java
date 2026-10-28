package io.ledgerforge.plugin;

import io.ledgerforge.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class PluginRejectedException extends ApiException {

    public PluginRejectedException(String pluginName, String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PLUGIN_REJECTED", pluginName + ": " + reason);
    }
}
