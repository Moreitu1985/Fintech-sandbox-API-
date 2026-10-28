package io.ledgerforge.plugin;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PluginController {

    public record PluginInfo(String name, String description) {
    }

    private final List<TransferPlugin> plugins;

    public PluginController(List<TransferPlugin> plugins) {
        this.plugins = plugins;
    }

    @Operation(summary = "List the plugins active on this platform")
    @GetMapping("/v1/plugins")
    public List<PluginInfo> list() {
        return plugins.stream().map(p -> new PluginInfo(p.name(), p.description())).toList();
    }
}
