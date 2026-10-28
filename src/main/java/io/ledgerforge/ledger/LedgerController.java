package io.ledgerforge.ledger;

import io.ledgerforge.ledger.dto.LedgerDtos.AccountResponse;
import io.ledgerforge.ledger.dto.LedgerDtos.CreateAccountRequest;
import io.ledgerforge.ledger.dto.LedgerDtos.EntryResponse;
import io.ledgerforge.ledger.dto.LedgerDtos.IntegrityReport;
import io.ledgerforge.ledger.dto.LedgerDtos.PageResponse;
import io.ledgerforge.ledger.dto.LedgerDtos.TransferRequest;
import io.ledgerforge.ledger.dto.LedgerDtos.TransferResponse;
import io.ledgerforge.security.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Tenant-facing API. The tenant always comes from the authenticated API key, never from the request. */
@RestController
@RequestMapping("/v1")
public class LedgerController {

    private final AccountService accounts;
    private final TransferService transfers;
    private final IntegrityService integrity;

    public LedgerController(AccountService accounts, TransferService transfers, IntegrityService integrity) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.integrity = integrity;
    }

    @Operation(summary = "Open an account")
    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> openAccount(@AuthenticationPrincipal TenantPrincipal caller,
                                                       @Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.open(caller.tenantId(), request);
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @Operation(summary = "List accounts")
    @GetMapping("/accounts")
    public PageResponse<AccountResponse> listAccounts(@AuthenticationPrincipal TenantPrincipal caller,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(accounts.list(caller.tenantId(), page, size), AccountResponse::from);
    }

    @Operation(summary = "Get an account and its balance")
    @GetMapping("/accounts/{accountId}")
    public AccountResponse getAccount(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID accountId) {
        return AccountResponse.from(accounts.get(caller.tenantId(), accountId));
    }

    @Operation(summary = "Account statement, newest first")
    @GetMapping("/accounts/{accountId}/entries")
    public PageResponse<EntryResponse> entries(@AuthenticationPrincipal TenantPrincipal caller,
                                               @PathVariable UUID accountId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(accounts.entries(caller.tenantId(), accountId, page, size), EntryResponse::from);
    }

    @Operation(summary = "Transfer money between two accounts",
            description = "Safe to retry: send the same Idempotency-Key and the original result is returned.")
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal TenantPrincipal caller,
            @Parameter(description = "Unique per logical transfer, 8-64 chars") @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransferRequest request) {
        TransferService.Result result = transfers.transfer(caller.tenantId(), key, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.transfer());
        }
        return ResponseEntity.created(URI.create("/v1/transfers/" + result.transfer().id())).body(result.transfer());
    }

    @Operation(summary = "Get a transfer")
    @GetMapping("/transfers/{transferId}")
    public TransferResponse getTransfer(@AuthenticationPrincipal TenantPrincipal caller,
                                        @PathVariable UUID transferId) {
        return transfers.get(caller.tenantId(), transferId);
    }

    @Operation(summary = "Prove the ledger is balanced and the audit chain is intact")
    @GetMapping("/ledger/integrity")
    public IntegrityReport integrity(@AuthenticationPrincipal TenantPrincipal caller) {
        return integrity.check(caller.tenantId());
    }
}
