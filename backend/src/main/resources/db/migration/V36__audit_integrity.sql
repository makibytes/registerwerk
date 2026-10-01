-- Phase 6 / K4 (6-11, 6-12): audit integrity. No rewrite of existing rows; nothing is dropped.

-- Per-row canonical-envelope version (1 = legacy eventType/subject/payload only, 2 = + actor, role,
-- occurred_at, correlation, reversal). The verifier selects the algorithm by this column.
ALTER TABLE audit_event ADD COLUMN canon_version SMALLINT NOT NULL DEFAULT 1;
-- Operational insert time (event time is occurred_at, captured at publish).
ALTER TABLE audit_event ADD COLUMN recorded_at TIMESTAMPTZ;
ALTER TABLE audit_event ALTER COLUMN actor_role TYPE VARCHAR(64);

-- Tip: sequence + last update time next to the hash (sequence NULL until the next append).
ALTER TABLE audit_chain_tip ADD COLUMN sequence_no BIGINT;
ALTER TABLE audit_chain_tip ADD COLUMN updated_at TIMESTAMPTZ;

-- Signing watermark: first sequence_no appended while signing was enabled. Write-once.
CREATE TABLE audit_chain_meta (
    id              BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (id),
    signing_from_seq BIGINT
);
INSERT INTO audit_chain_meta (id, signing_from_seq) VALUES (TRUE, NULL);

CREATE OR REPLACE FUNCTION audit_meta_write_once()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.signing_from_seq IS NOT NULL THEN
        RAISE EXCEPTION 'audit_chain_meta.signing_from_seq is write-once. Operation: %', TG_OP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_audit_chain_meta_write_once
    BEFORE UPDATE OR DELETE ON audit_chain_meta
    FOR EACH ROW EXECUTE FUNCTION audit_meta_write_once();

-- Signed anchors of the chain tip (DAILY) and archive markers (ARCHIVED_UP_TO).
CREATE TABLE audit_chain_anchor (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('DAILY', 'ARCHIVED_UP_TO')),
    anchor_date DATE NOT NULL,
    sequence_no BIGINT NOT NULL,
    entry_hash  BYTEA NOT NULL,
    sig         BYTEA,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, anchor_date)
);
CREATE OR REPLACE FUNCTION audit_anchor_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_chain_anchor rows are immutable. Operation: %', TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_chain_anchor_immutable
    BEFORE UPDATE OR DELETE ON audit_chain_anchor
    FOR EACH ROW EXECUTE FUNCTION audit_anchor_immutable();

-- Poison audit publications moved out of the retry loop (never retried forever, never silently dropped).
CREATE TABLE audit_event_dead_letter (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id   UUID NOT NULL,
    listener_id      TEXT NOT NULL,
    event_type       TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    attempts         INTEGER NOT NULL,
    moved_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
-- Statement-level TRUNCATE guard (the row-level WORM trigger does not fire on TRUNCATE).
-- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
CREATE OR REPLACE FUNCTION audit_event_no_truncate()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
    RAISE EXCEPTION 'audit_event must not be truncated (eWpRV §6).';
END;
$$;

-- Privileges: correct once runtime and migrator logins are split (T6-17); a no-op against the
-- table owner today, which is why AuditReadinessCheck reports the ownership problem.
DO $$
DECLARE part REGCLASS;
BEGIN
    -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
    REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM registerwerk_app;
    -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
    REVOKE UPDATE, DELETE, TRUNCATE ON audit_chain_anchor FROM registerwerk_app;
    -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
    CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON audit_event
        -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
        FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate();
    FOR part IN SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'audit_event'::regclass LOOP
        -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %s FROM registerwerk_app', part);
        -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
        EXECUTE format('CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON %s '
                       -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
                       'FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate()', part);
    END LOOP;
END
$$;

CREATE OR REPLACE FUNCTION audit_event_ensure_partitions(months_ahead INT DEFAULT 6)
    RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    FOR i IN 0..months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := 'audit_event_' || to_char(month_start, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
                part_name, month_start, month_end);
            -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM registerwerk_app', part_name);
            -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
            EXECUTE format('CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON %I '
                           -- migration-safety: ack (privilege revoke / guard trigger against TRUNCATE; removes no data)
                           'FOR EACH STATEMENT EXECUTE FUNCTION audit_event_no_truncate()', part_name);
        END IF;
    END LOOP;
END;
$$;
