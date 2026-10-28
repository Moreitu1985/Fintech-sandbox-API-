-- H2 is used only for local development and tests. Append-only triggers are
-- enforced on PostgreSQL (see db/migration/postgresql/V2__append_only_guards.sql).
-- The audit-chain tamper test relies on being able to modify rows here.
SELECT 1;
