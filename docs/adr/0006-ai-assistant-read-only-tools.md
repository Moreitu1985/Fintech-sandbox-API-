# ADR 0006: AI ledger assistant through read-only, tenant-scoped tools

**Status:** Accepted

## Context

Fintech operators want to ask questions such as "which transfers were flagged for review this week?" without writing queries. A large language model can answer them, but it must never see another tenant's data, move money, or be steered by text hidden in the data (prompt injection), and its cost must stay bounded.

## Options

1. **Text-to-SQL:** the model writes SQL. Flexible, but one bad query can read other tenants' rows or overload the database, and injected text can shape the SQL.
2. **Send the data in the prompt:** simple, but leaks more than needed and doesn't scale.
3. **Tool use with fixed, read-only functions:** the model chooses from a few tools; Java runs them.

## Decision

Option 3, with Claude's Messages API tool use.

- **Five read-only tools**: `list_accounts`, `account_statement`, `transfer_summary`, `fraud_events`, `ledger_integrity`. There are no write tools, so the assistant cannot move money however it is prompted.
- **Tenant scoping is outside the model's control.** The tenant comes from the authenticated API key and is passed by the service. Account ids the model proposes are looked up within that tenant, and foreign ids look like missing ones.
- **Prompt injection is contained, not just discouraged.** The system prompt marks tool results as untrusted data, but the real protection is structural: even a fully manipulated model can only call read-only tools on the caller's own data, and its output is plain text returned to that same caller.
- **Exact money.** Amounts reach the model as decimal strings ("25.50"), so it never does cent arithmetic.
- **Bounded cost and time:** at most 5 model/tool rounds per question, a per-tenant limit of 10 questions a minute, 1,024 output tokens, timeouts, and no database transaction or lock held while waiting for the model.
- **Accountability:** every question is recorded in the tamper-evident audit chain with the tools used.
- **Off by default.** The endpoint exists only with `ASSISTANT_ENABLED=true` and an `ANTHROPIC_API_KEY`. Tests use a scripted fake of the Messages API, so CI needs no key and makes no paid calls.
- The default model is `claude-haiku-5-5` (fast and inexpensive for lookups), configurable with `ASSISTANT_MODEL`.

## Consequences

- Questions the tools can't answer get "I don't have that", which is preferable to a guess.
- Tool results are sent to an external AI provider. In production this needs a data-processing agreement and a POPIA review; only the tenant's own ledger data is sent, never another tenant's.
- New questions need new tools, which is deliberate: each tool is reviewed and tested before the model can use it.
