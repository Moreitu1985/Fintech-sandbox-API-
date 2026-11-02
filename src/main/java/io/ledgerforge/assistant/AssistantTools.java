package io.ledgerforge.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ledgerforge.audit.AuditEvent;
import io.ledgerforge.audit.AuditEventRepository;
import io.ledgerforge.ledger.Account;
import io.ledgerforge.ledger.AccountRepository;
import io.ledgerforge.ledger.IntegrityService;
import io.ledgerforge.ledger.LedgerTransaction;
import io.ledgerforge.ledger.LedgerTransactionRepository;
import io.ledgerforge.ledger.Posting;
import io.ledgerforge.ledger.PostingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only things the assistant can do: a handful of READ-ONLY queries over ONE tenant's data.
 *
 * The tenant always comes from the authenticated API key, passed in by the service, and is never
 * read from the model's input, so a manipulated model still cannot see another tenant's data.
 * Account ids proposed by the model are looked up within the tenant; foreign ids look like missing ones.
 * There are no write tools, so the assistant cannot move money or change anything.
 */
@Component
public class AssistantTools {

    public record ToolResult(String content, boolean isError) {
    }

    private static final int MAX_RESULT_CHARS = 20_000;

    /** Tool definitions sent to Claude (name, description, JSON schema for input). */
    static final String DEFINITIONS = """
            [
              {"name": "list_accounts",
               "description": "List this tenant's accounts with id, name, currency, current balance and type (customer or funding). Use it first to find account ids.",
               "input_schema": {"type": "object", "properties": {}}},
              {"name": "account_statement",
               "description": "Most recent ledger entries for one account, newest first: date, signed amount, counterparty account id, description and reference.",
               "input_schema": {"type": "object",
                 "properties": {
                   "account_id": {"type": "string", "description": "Account id from list_accounts"},
                   "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "Entries to return, default 20"}},
                 "required": ["account_id"]}},
              {"name": "transfer_summary",
               "description": "Per-account totals sent and received, and number of entries, over the last N days.",
               "input_schema": {"type": "object",
                 "properties": {"days": {"type": "integer", "minimum": 1, "maximum": 365, "description": "Look-back window, default 30"}}}},
              {"name": "fraud_events",
               "description": "Transfers the fraud model flagged in the last N days: REVIEW (went through, needs a human check) or BLOCK (refused). Includes score, amount, accounts and the model's reasons. At most 50, newest first.",
               "input_schema": {"type": "object",
                 "properties": {
                   "days": {"type": "integer", "minimum": 1, "maximum": 90, "description": "Look-back window, default 7"},
                   "decision": {"type": "string", "enum": ["REVIEW", "BLOCK", "ANY"], "description": "Filter, default ANY"}}}},
              {"name": "ledger_integrity",
               "description": "Check that the books balance, balances match their entries, and the tamper-evident audit chain is intact.",
               "input_schema": {"type": "object", "properties": {}}}
            ]
            """;

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final LedgerTransactionRepository transactions;
    private final AuditEventRepository auditEvents;
    private final IntegrityService integrity;
    private final ObjectMapper json;

    public AssistantTools(AccountRepository accounts, PostingRepository postings,
                          LedgerTransactionRepository transactions, AuditEventRepository auditEvents,
                          IntegrityService integrity, ObjectMapper json) {
        this.accounts = accounts;
        this.postings = postings;
        this.transactions = transactions;
        this.auditEvents = auditEvents;
        this.integrity = integrity;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ToolResult execute(UUID tenantId, String name, JsonNode input) {
        JsonNode args = input == null || input.isNull() ? json.createObjectNode() : input;
        try {
            JsonNode result = switch (name) {
                case "list_accounts" -> listAccounts(tenantId);
                case "account_statement" -> statement(tenantId, args);
                case "transfer_summary" -> transferSummary(tenantId, intArg(args, "days", 30, 1, 365));
                case "fraud_events" -> fraudEvents(tenantId, intArg(args, "days", 7, 1, 90),
                        args.path("decision").asText("ANY"));
                case "ledger_integrity" -> json.valueToTree(integrity.check(tenantId));
                default -> throw new ToolInputException("Unknown tool: " + name);
            };
            String text = json.writeValueAsString(result);
            if (text.length() > MAX_RESULT_CHARS) {
                text = text.substring(0, MAX_RESULT_CHARS) + "... [truncated]";
            }
            return new ToolResult(text, false);
        } catch (ToolInputException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult("The tool failed. Try a different question.", true);
        }
    }

    private JsonNode listAccounts(UUID tenantId) {
        var page = accounts.findByTenantId(tenantId, PageRequest.of(0, 100, Sort.by("name", "id")));
        ArrayNode list = json.createArrayNode();
        for (Account a : page.getContent()) {
            list.addObject()
                    .put("id", a.getId().toString())
                    .put("name", a.getName())
                    .put("currency", a.getCurrency())
                    .put("balance", major(a.getBalance()))
                    .put("type", a.isAllowNegative() ? "funding" : "customer");
        }
        ObjectNode out = json.createObjectNode();
        out.set("accounts", list);
        out.put("total_accounts", page.getTotalElements());
        if (page.getTotalElements() > list.size()) {
            out.put("note", "Only the first " + list.size() + " accounts are listed.");
        }
        return out;
    }

    private JsonNode statement(UUID tenantId, JsonNode args) {
        UUID accountId = uuidArg(args, "account_id");
        Account account = accounts.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new ToolInputException("No account with that id. Use list_accounts to find ids."));
        int limit = intArg(args, "limit", 20, 1, 50);
        List<Posting> entries = postings.findByAccountIdAndTenantId(accountId, tenantId,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).getContent();
        Map<UUID, LedgerTransaction> txns = transactions.findAllById(
                        entries.stream().map(Posting::getTransactionId).distinct().toList()).stream()
                .collect(Collectors.toMap(LedgerTransaction::getId, Function.identity()));

        ArrayNode list = json.createArrayNode();
        for (Posting p : entries) {
            LedgerTransaction t = txns.get(p.getTransactionId());
            ObjectNode row = list.addObject()
                    .put("date", p.getCreatedAt().toString())
                    .put("amount", major(p.getAmount()))
                    .put("direction", p.getAmount() < 0 ? "sent" : "received");
            if (t != null) {
                UUID counterparty = p.getAmount() < 0 ? t.getToAccountId() : t.getFromAccountId();
                row.put("counterparty_account_id", counterparty.toString())
                        .put("transfer_id", t.getId().toString());
                if (t.getDescription() != null) {
                    row.put("description", t.getDescription());
                }
                if (t.getReference() != null) {
                    row.put("reference", t.getReference());
                }
            }
        }
        ObjectNode out = json.createObjectNode()
                .put("account_id", account.getId().toString())
                .put("account_name", account.getName())
                .put("currency", account.getCurrency())
                .put("current_balance", major(account.getBalance()));
        out.set("entries", list);
        return out;
    }

    private JsonNode transferSummary(UUID tenantId, int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Map<UUID, Account> byId = new HashMap<>();
        accounts.findByTenantId(tenantId, PageRequest.of(0, 1000)).forEach(a -> byId.put(a.getId(), a));

        ArrayNode list = json.createArrayNode();
        for (Object[] row : postings.summarizeByAccount(tenantId, since)) {
            UUID accountId = (UUID) row[0];
            Account a = byId.get(accountId);
            ObjectNode item = list.addObject()
                    .put("account_id", accountId.toString())
                    .put("entries", ((Number) row[1]).longValue())
                    .put("sent", major(((Number) row[2]).longValue()))
                    .put("received", major(((Number) row[3]).longValue()));
            if (a != null) {
                item.put("account_name", a.getName()).put("currency", a.getCurrency());
            }
        }
        ObjectNode out = json.createObjectNode().put("days", days).put("since", since.toString());
        out.set("accounts", list);
        return out;
    }

    private JsonNode fraudEvents(UUID tenantId, int days, String decision) {
        List<String> types = switch (decision.toUpperCase()) {
            case "REVIEW" -> List.of("FRAUD_REVIEW");
            case "BLOCK" -> List.of("FRAUD_BLOCK");
            case "ANY" -> List.of("FRAUD_REVIEW", "FRAUD_BLOCK");
            default -> throw new ToolInputException("decision must be REVIEW, BLOCK or ANY");
        };
        long since = Instant.now().minus(Duration.ofDays(days)).toEpochMilli();
        ArrayNode list = json.createArrayNode();
        for (AuditEvent e : auditEvents
                .findTop50ByTenantIdAndEventTypeInAndCreatedAtMillisGreaterThanEqualOrderByChainIndexDesc(
                        tenantId, types, since)) {
            Map<String, String> f = parseEvidence(e.getPayload());
            ObjectNode item = list.addObject()
                    .put("time", Instant.ofEpochMilli(e.getCreatedAtMillis()).toString())
                    .put("decision", e.getEventType().equals("FRAUD_BLOCK") ? "BLOCK" : "REVIEW")
                    .put("from_account_id", f.getOrDefault("from", ""))
                    .put("to_account_id", f.getOrDefault("to", ""))
                    .put("currency", f.getOrDefault("currency", ""))
                    .put("score", f.getOrDefault("score", ""))
                    .put("model", f.getOrDefault("model", ""))
                    .put("reasons", f.getOrDefault("reasons", ""));
            try {
                item.put("amount", major(Long.parseLong(f.getOrDefault("amount", ""))));
            } catch (NumberFormatException ignored) {
                item.put("amount", "unknown");
            }
        }
        ObjectNode out = json.createObjectNode().put("days", days).put("decision", decision.toUpperCase())
                .put("count", list.size());
        out.set("events", list);
        return out;
    }

    /** Parses "from=..;to=..;amount=..;...;reasons=free text" (reasons last, may itself contain ';'). */
    static Map<String, String> parseEvidence(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        String head = payload;
        int r = payload.indexOf(";reasons=");
        if (r >= 0) {
            fields.put("reasons", payload.substring(r + ";reasons=".length()));
            head = payload.substring(0, r);
        }
        for (String part : head.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return fields;
    }

    /** Minor units to a plain decimal string, e.g. -2550 to "-25.50", so the model never does cent arithmetic. */
    static String major(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static int intArg(JsonNode args, String name, int def, int min, int max) {
        JsonNode v = args.get(name);
        if (v == null || v.isNull()) {
            return def;
        }
        if (!v.canConvertToInt()) {
            throw new ToolInputException(name + " must be a whole number between " + min + " and " + max);
        }
        return Math.min(max, Math.max(min, v.asInt()));
    }

    private static UUID uuidArg(JsonNode args, String name) {
        try {
            return UUID.fromString(args.path(name).asText());
        } catch (IllegalArgumentException e) {
            throw new ToolInputException(name + " must be an account id from list_accounts");
        }
    }

    static final class ToolInputException extends RuntimeException {
        ToolInputException(String message) {
            super(message);
        }
    }
}
