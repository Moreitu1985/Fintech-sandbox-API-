package io.ledgerforge.assistant;

import io.ledgerforge.security.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "ledgerforge.assistant", name = "enabled", havingValue = "true")
public class AssistantController {

    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    @Operation(summary = "Ask a plain-English question about your ledger",
            description = "Read-only. The assistant uses tools scoped to your tenant to answer questions such as "
                    + "\"Which transfers were flagged for review this week?\" or \"How much did each account send "
                    + "in the last 30 days?\". Every question is recorded in the audit chain.")
    @PostMapping("/v1/assistant/ask")
    public AssistantService.Answer ask(@AuthenticationPrincipal TenantPrincipal caller,
                                       @Valid @RequestBody AskRequest request) {
        return assistant.ask(caller.tenantId(), request.question().strip());
    }
}
