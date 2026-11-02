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
- **Prompt injection is contained, not just discouraged.** Tool results are wrapped as `{"untrusted_data": ...}` and the system prompt says never to follow instructions inside them. The real protection is structural: even a fully manipulated model can only call read-only tools on the caller's own data, and its output goes back to that same caller. Links and images are stripped from answers, so injected URLs can't exfiltrate data if a client renders the answer as markdown.
- **Exact money.** Amounts reach the model as decimal strings ("25.50"), so it never does cent arithmetic.
- **Bounded cost and load.** Per question: at most 5 model calls, with tools switched off (`tool_choice: none`) on the last so Claude must answer; at most 4 tool calls per round; 60,000 characters of tool data; 1,024 output tokens; a 60-second deadline. Per tenant: 10 questions a minute. Per server: 8 questions in flight, so slow model calls can't exhaust request threads. No database transaction or lock is held while waiting for the model.
- **Honest completion.** Only `end_turn` or `stop_sequence` counts as a complete answer; `max_tokens`, `refusal` or hitting a limit returns `complete: false`.
- **Robust tools.** Each tool runs in its own read-only transaction and never throws: unknown tools, bad input and failures become `is_error` results the model can recover from.
- **Accountability:** every question, including ones that fail part-way after data was sent to the model, is recorded in the tamper-evident audit chain with its outcome, the tools used and the token counts.
- **Off by default.** The endpoint exists only with `ASSISTANT_ENABLED=true` and an `ANTHROPIC_API_KEY`. Tests use a scripted fake of the Messages API, so CI needs no key and makes no paid calls.
- The default model is `claude-haiku-5-5` (fast and inexpensive for lookups), configurable with `ASSISTANT_MODEL`.

## Consequences

- Questions the tools can't answer get "I don't have that", which is preferable to a guess.
- Tool results are sent to an external AI provider. In production this needs a data-processing agreement and a POPIA review; only the tenant's own ledger data is sent, never another tenant's.
- New questions need new tools, which is deliberate: each tool is reviewed and tested before the model can use it.
- `ledger_integrity` re-verifies the whole audit chain, which is O(n) in the tenant's history; large tenants will need checkpointed verification (also noted in ADR 0004).
