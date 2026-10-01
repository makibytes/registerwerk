-- 7A-05: dedup key for audit appends. The partitioned audit_event cannot carry a global unique key
-- without occurred_at, so each AuditRecord's recordId is claimed here in the same transaction as the append.
-- Not WORM: rows are pruned after 30 days by AuditRecordIdPruneJob.
CREATE TABLE audit_event_record_id (
    record_id   UUID PRIMARY KEY,
    sequence_no BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_event_record_id_recorded_at ON audit_event_record_id (recorded_at);

-- 7B-04: persisted verdict of every chain verification run (insert-only).
CREATE TABLE audit_chain_verification (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ran_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid            BOOLEAN NOT NULL,
    rows_checked     BIGINT NOT NULL,
    first_broken_seq BIGINT,
    reason           TEXT,
    source           VARCHAR(20) NOT NULL CHECK (source IN ('NIGHTLY', 'ON_DEMAND'))
);
CREATE INDEX idx_audit_chain_verification_ran_at ON audit_chain_verification (ran_at DESC);

-- Dual-control acknowledgement of a broken verdict.
CREATE TABLE audit_chain_verification_ack (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    verification_id UUID NOT NULL UNIQUE REFERENCES audit_chain_verification (id),
    acked_by        UUID,
    approver_id     UUID,
    note            TEXT,
    acked_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION audit_verification_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable. Operation: %', TG_TABLE_NAME, TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_chain_verification_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_verification
    FOR EACH ROW EXECUTE FUNCTION audit_verification_immutable();
CREATE TRIGGER trg_audit_chain_verification_ack_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_verification_ack
    FOR EACH ROW EXECUTE FUNCTION audit_verification_immutable();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'registerwerk_app') THEN
        -- migration-safety: ack (privilege revoke on new insert-only tables; removes no data)
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_chain_verification FROM registerwerk_app;
        -- migration-safety: ack (privilege revoke on new insert-only tables; removes no data)
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_chain_verification_ack FROM registerwerk_app;
    END IF;
END
$$;
