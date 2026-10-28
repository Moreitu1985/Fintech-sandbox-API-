package io.ledgerforge.ledger.dto;

import io.ledgerforge.ledger.Account;
import io.ledgerforge.ledger.LedgerTransaction;
import io.ledgerforge.ledger.Posting;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Request and response shapes. Entities never leave the service layer. Amounts are minor units. */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    private static final String CURRENCY = "^[A-Z]{3}$";
    private static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]*$";

    public record CreateAccountRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARS) String name,
            @NotNull @Pattern(regexp = CURRENCY, message = "must be a 3-letter ISO code such as ZAR") String currency,
            Boolean allowNegative) {
    }

    public record AccountResponse(UUID id, String name, String currency, boolean allowNegative, long balance,
                                  Instant createdAt) {
        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getName(), a.getCurrency(), a.isAllowNegative(),
                    a.getBalance(), a.getCreatedAt());
        }
    }

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Positive @Max(1_000_000_000_000L) Long amount,
            @NotNull @Pattern(regexp = CURRENCY, message = "must be a 3-letter ISO code such as ZAR") String currency,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]*$") String reference,
            @Size(max = 140) @Pattern(regexp = NO_CONTROL_CHARS) String description) {
    }

    public record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, long amount, String currency,
                                   String reference, String description, String status, Instant createdAt) {
        public static TransferResponse from(LedgerTransaction t) {
            return new TransferResponse(t.getId(), t.getFromAccountId(), t.getToAccountId(), t.getAmount(),
                    t.getCurrency(), t.getReference(), t.getDescription(), "POSTED", t.getCreatedAt());
        }
    }

    public record EntryResponse(UUID transactionId, long amount, Instant createdAt) {
        public static EntryResponse from(Posting p) {
            return new EntryResponse(p.getTransactionId(), p.getAmount(), p.getCreatedAt());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
        public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }

    public record IntegrityReport(
            boolean healthy,
            long postingsSum,
            long accountsChecked,
            List<UUID> balanceMismatches,
            boolean auditChainValid,
            long auditEventsChecked,
            Long auditFirstBrokenIndex,
            Instant checkedAt) {
    }
}
