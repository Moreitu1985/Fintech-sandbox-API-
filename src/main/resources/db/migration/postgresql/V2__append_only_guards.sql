-- Defence in depth: ledger history is append-only at the database level.
-- Even a compromised application account cannot rewrite or delete postings,
-- transactions or audit events. Corrections are made with new, reversing entries.

CREATE OR REPLACE FUNCTION ledgerforge_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledgerforge_forbid_mutation();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledgerforge_forbid_mutation();

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION ledgerforge_forbid_mutation();
