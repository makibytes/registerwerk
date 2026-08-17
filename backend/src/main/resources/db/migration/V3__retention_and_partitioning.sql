-- ═══════════════════════════════════════════════════════════════════════════
-- V3 — Production-readiness Phase 1: partition management + data retention.
--
-- Four things happen in this migration, in order:
--   1. A GENERIC partition-management mechanism (rw_ensure_monthly_partitions /
--      rw_retire_partitions), generalizing the pattern already used for
--      audit_event (audit_event_ensure_partitions, see V1 line ~1476). This
--      migration does NOT touch audit_event or its function/job in any way —
--      audit_event is WORM (trg_audit_event_immutable) and subject to a
--      10-year statutory retention (eWpG §15(3) / TVTG §33). Retirement is
--      never wired for audit_event. Only the generic mechanism is built here,
--      proven against the two tables converted below.
--   2. token_transfer and blockchain_transaction are converted from plain
--      heap tables to RANGE-partitioned tables (monthly, keyed on
--      occurred_at / created_at respectively), using the create-new /
--      batch-copy / rename-swap pattern so it works whether the table
--      currently holds zero rows (fresh dev DB) or many.
--   3. login_attempt gets the lookup index it was always missing.
--   4. The never-actually-wired-up Quartz persistent job store (10 qrtz_* tables) is dropped —
--      see section 4 below for the investigation that established it was genuinely dead.
--
-- rw_retire_partitions only ever DETACHes (renaming the detached partition
-- to an "*_archived_YYYY_MM" table) — it never DROPs. Turning a detached
-- partition into an actual DROP is a deliberate, separate, human/ops decision
-- (a later migration or manual DBA action), not something this migration
-- automates. Nothing in this file schedules automatic retirement for
-- token_transfer or blockchain_transaction either — see
-- infrastructure/PartitionMaintenanceJob.java, which only ever calls
-- rw_ensure_monthly_partitions (bootstrapping future months), never
-- rw_retire_partitions. The retirement function exists and is exercised by
-- RetentionAndPartitioningIT, but nothing calls it in production yet.
-- ═══════════════════════════════════════════════════════════════════════════


-- ═══════════════════════════════════════════════════════════════════════════
-- 1. GENERIC PARTITION MANAGER
-- ═══════════════════════════════════════════════════════════════════════════

-- Generalizes audit_event_ensure_partitions() (V1) to any RANGE-partitioned
-- table keyed on a single timestamptz column. p_time_column is not used to
-- build the DDL (the partition key is whatever the table was declared with)
-- — it is a defensive cross-check: if the caller names the wrong column we
-- fail loudly rather than silently creating partitions against the wrong
-- semantics.
CREATE OR REPLACE FUNCTION rw_ensure_monthly_partitions(
    p_table        regclass,
    p_time_column  text,
    p_months_ahead int DEFAULT 6
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    schema_name TEXT;
    bare_name   TEXT;
    actual_col  TEXT;
    month_start DATE;
    month_end   DATE;
    part_name   TEXT;
BEGIN
    SELECT n.nspname, c.relname INTO schema_name, bare_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_table;

    SELECT a.attname INTO actual_col
    FROM pg_partitioned_table pt
    -- partattrs is an int2vector, which (unlike a normal array) is 0-indexed, not 1-indexed.
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = p_table;

    IF actual_col IS NULL THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is not a partitioned table', p_table;
    ELSIF actual_col IS DISTINCT FROM p_time_column THEN
        RAISE EXCEPTION 'rw_ensure_monthly_partitions: % is partitioned on column % not %',
            p_table, actual_col, p_time_column;
    END IF;

    FOR i IN 0..p_months_ahead LOOP
        month_start := date_trunc('month', CURRENT_DATE + (i || ' months')::INTERVAL)::DATE;
        month_end   := (month_start + INTERVAL '1 month')::DATE;
        part_name   := bare_name || '_' || to_char(month_start, 'YYYY_MM');
        BEGIN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                schema_name, part_name, p_table::text, month_start, month_end
            );
        EXCEPTION WHEN duplicate_table THEN
            -- already exists
        END;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION rw_ensure_monthly_partitions(regclass, text, int) IS
    'Generic monthly RANGE-partition bootstrapper. Same pattern as '
    'audit_event_ensure_partitions() (V1) but parameterized over any table/column. '
    'Idempotent — safe to call repeatedly (e.g. on every app startup and monthly via cron).';

-- DETACH-only partition retirement. p_mode exists so the intent is explicit at every call
-- site, but 'DETACH' is the only implemented value on purpose: an automatic DROP path would
-- let a misconfigured retention window destroy data that is still under legal hold (see
-- kyc.api.JurisdictionRequirementConfig — DE/LI require 10-year retention). Detaching keeps
-- the data fully intact and queryable (under its new archived name) with zero data loss;
-- turning that into an actual DROP is a separate, explicit, auditable operator action.
CREATE OR REPLACE FUNCTION rw_retire_partitions(
    p_table       regclass,
    p_time_column text,
    p_keep_months int,
    p_mode        text DEFAULT 'DETACH'
) RETURNS SETOF text LANGUAGE plpgsql AS $$
DECLARE
    schema_name TEXT;
    bare_name   TEXT;
    actual_col  TEXT;
    cutoff      DATE;
    child       RECORD;
    part_month  DATE;
    new_name    TEXT;
BEGIN
    IF p_mode <> 'DETACH' THEN
        RAISE EXCEPTION 'rw_retire_partitions: mode % is not supported. Only DETACH is '
            'implemented — an explicit human/ops decision must gate any DROP of retired '
            'partition data (see V3__retention_and_partitioning.sql header).', p_mode;
    END IF;

    SELECT n.nspname, c.relname INTO schema_name, bare_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_table;

    SELECT a.attname INTO actual_col
    FROM pg_partitioned_table pt
    -- partattrs is an int2vector, which (unlike a normal array) is 0-indexed, not 1-indexed.
    JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
    WHERE pt.partrelid = p_table;

    IF actual_col IS NULL THEN
        RAISE EXCEPTION 'rw_retire_partitions: % is not a partitioned table', p_table;
    ELSIF actual_col IS DISTINCT FROM p_time_column THEN
        RAISE EXCEPTION 'rw_retire_partitions: % is partitioned on column % not %',
            p_table, actual_col, p_time_column;
    END IF;

    cutoff := date_trunc('month', now())::DATE - (p_keep_months || ' months')::INTERVAL;

    -- Only touch children whose name matches our own "<table>_YYYY_MM" naming convention —
    -- this deliberately skips the DEFAULT partition (named "<table>_default"), which has no
    -- parseable date bound and must never be silently detached.
    FOR child IN
        SELECT ch.relname AS child_name, chn.nspname AS child_schema
        FROM pg_inherits i
        JOIN pg_class ch ON ch.oid = i.inhrelid
        JOIN pg_namespace chn ON chn.oid = ch.relnamespace
        WHERE i.inhparent = p_table
          AND ch.relname ~ (bare_name || '_[0-9]{4}_[0-9]{2}$')
        ORDER BY ch.relname
    LOOP
        part_month := to_date(substring(child.child_name FROM '([0-9]{4}_[0-9]{2})$'), 'YYYY_MM');
        IF part_month + INTERVAL '1 month' <= cutoff THEN
            new_name := bare_name || '_archived_' || to_char(part_month, 'YYYY_MM');
            EXECUTE format('ALTER TABLE %s DETACH PARTITION %I.%I',
                p_table::text, child.child_schema, child.child_name);
            EXECUTE format('ALTER TABLE %I.%I RENAME TO %I',
                child.child_schema, child.child_name, new_name);
            RETURN NEXT new_name;
        END IF;
    END LOOP;
    RETURN;
END;
$$;

COMMENT ON FUNCTION rw_retire_partitions(regclass, text, int, text) IS
    'Detaches (never drops) monthly partitions older than p_keep_months, renaming each to '
    '"<table>_archived_YYYY_MM". Data is preserved intact and remains directly queryable under '
    'its new name; it is simply no longer part of the partitioned parent (and therefore no '
    'longer scanned by normal queries against it). DROP is a deliberate separate operator step, '
    'never automated here. Not called anywhere in application code for token_transfer or '
    'blockchain_transaction in Phase 1 — see infrastructure/PartitionMaintenanceJob.java.';


-- ═══════════════════════════════════════════════════════════════════════════
-- 2a. token_transfer → RANGE-partitioned on occurred_at
-- ═══════════════════════════════════════════════════════════════════════════

-- New partitioned table. Column list, CHECK, and both UNIQUE constraints are copied verbatim
-- from V1's token_transfer (lines ~574-600) with two required changes:
--   * the PRIMARY KEY becomes (id, occurred_at) — Postgres requires every unique/PK constraint
--     on a partitioned table to include the partition key. This means Postgres itself can no
--     longer guarantee "id" alone is globally unique; in practice id is a randomly generated
--     UUID (gen_random_uuid() / GenerationType.UUID), so collision risk is negligible and this
--     is the standard, documented trade-off for UUID-keyed partitioned tables in Postgres.
--     Nothing references token_transfer.id via a foreign key (travel_rule_message.token_transfer_id
--     is a plain, unconstrained UUID column, resolved at the application layer — verified by
--     grepping V1/V2 for "REFERENCES token_transfer" before writing this), so widening the PK
--     is safe.
--   * uq_transfer_evm / uq_transfer_solana gain occurred_at for the same reason. tx_hash /
--     log_index / slot are themselves derived from the same on-chain event as occurred_at, so
--     this does not practically weaken the deduplication these constraints exist for.
-- Constraint/index names get a temporary "_new" suffix because the original names are still
-- held by the not-yet-dropped old table; they are renamed back to the canonical V1 names once
-- the old table is dropped, further down.
CREATE TABLE token_transfer_new (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    asset_id         UUID        REFERENCES asset(id),
    deployment_id    UUID        REFERENCES asset_deployment(id),
    chain_config_id  UUID        NOT NULL REFERENCES chain_config(id),
    contract_address VARCHAR(128) NOT NULL,
    from_address     VARCHAR(128),
    to_address       VARCHAR(128),
    token_id         NUMERIC,
    amount           NUMERIC(38,18),
    event_type       VARCHAR(10) NOT NULL,
    tx_hash          VARCHAR(128) NOT NULL,
    block_number     BIGINT,
    log_index        INT,
    slot             BIGINT,
    occurred_at      TIMESTAMPTZ NOT NULL,
    explorer_tx_url  VARCHAR(600),
    raw_data         JSONB,
    CONSTRAINT chk_event_type_new     CHECK (event_type IN ('MINT','TRANSFER','BURN')),
    CONSTRAINT tt_pkey_new             PRIMARY KEY (id, occurred_at),
    CONSTRAINT uq_transfer_evm_new    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index, occurred_at),
    CONSTRAINT uq_transfer_solana_new UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE token_transfer_new_default PARTITION OF token_transfer_new DEFAULT;

-- Swap names inside one short block: the new (currently empty, partitioned) table takes over
-- the "token_transfer" name; the old table (with all existing data, indexes and constraints
-- completely untouched so far) becomes "token_transfer_old". Application code keeps working
-- against "token_transfer" throughout — right after this, it is simply empty until the batch
-- copy below populates it.
ALTER TABLE token_transfer RENAME TO token_transfer_old;
ALTER TABLE token_transfer_new RENAME TO token_transfer;
ALTER TABLE token_transfer_new_default RENAME TO token_transfer_default;

-- Bootstrap every monthly partition the existing data needs (from its earliest occurred_at
-- through 6 months ahead of today) BEFORE copying any rows across. Creating the dated
-- partitions first means every copied row routes straight into its correct monthly partition;
-- doing this the other way around (copy first, partition later) would dump every row into the
-- DEFAULT partition, and Postgres then refuses to carve out a matching range partition
-- afterwards ("updated partition constraint for default partition would be violated") — the
-- exact trap AuditPartitionJob's own doc comment warns about for audit_event.
DO $$
DECLARE
    earliest    DATE;
    latest_need DATE;
    month_start DATE;
    part_name   TEXT;
BEGIN
    SELECT COALESCE(date_trunc('month', MIN(occurred_at)), date_trunc('month', now()))::DATE
    INTO earliest
    FROM token_transfer_old;

    latest_need := date_trunc('month', now() + INTERVAL '6 months')::DATE;

    month_start := earliest;
    WHILE month_start <= latest_need LOOP
        part_name := 'token_transfer_' || to_char(month_start, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF token_transfer FOR VALUES FROM (%L) TO (%L)',
            part_name, month_start, (month_start + INTERVAL '1 month')::DATE
        );
        month_start := (month_start + INTERVAL '1 month')::DATE;
    END LOOP;
END;
$$;

-- Belt-and-suspenders: also run the new generic bootstrapper. Every partition it wants to
-- create already exists from the loop above (IF NOT EXISTS makes this a no-op today), but this
-- both exercises rw_ensure_monthly_partitions against a real table inside this same migration
-- and matches the task requirement to bootstrap future months through the generic mechanism.
SELECT rw_ensure_monthly_partitions('token_transfer', 'occurred_at', 6);

-- Batch-copy existing rows in bounded chunks rather than one single INSERT ... SELECT, so no
-- single statement has to sort/hash/WAL-log the entire table at once. Note: because Flyway runs
-- this whole script as one migration (no manual follow-up steps are allowed — see file header),
-- these batches still commit together as a single transaction at the end of the migration, same
-- as every other DDL change in this schema; batching here bounds *per-statement* work/memory,
-- it does not achieve fully-online multi-transaction cutover. For this reference deployment
-- (Flyway runs once at startup, ahead of the app serving traffic) that is an acceptable trade-off.
DO $$
DECLARE
    batch_size INT := 5000;
    moved      INT;
BEGIN
    LOOP
        INSERT INTO token_transfer
            (id, asset_id, deployment_id, chain_config_id, contract_address, from_address,
             to_address, token_id, amount, event_type, tx_hash, block_number, log_index, slot,
             occurred_at, explorer_tx_url, raw_data)
        SELECT o.id, o.asset_id, o.deployment_id, o.chain_config_id, o.contract_address,
               o.from_address, o.to_address, o.token_id, o.amount, o.event_type, o.tx_hash,
               o.block_number, o.log_index, o.slot, o.occurred_at, o.explorer_tx_url, o.raw_data
        FROM token_transfer_old o
        WHERE NOT EXISTS (SELECT 1 FROM token_transfer n WHERE n.id = o.id)
        LIMIT batch_size;

        GET DIAGNOSTICS moved = ROW_COUNT;
        EXIT WHEN moved = 0;
    END LOOP;
END;
$$;

-- Old table (and the original index/constraint names it still holds) is no longer needed.
-- migration-safety: ack (batch-copy backfill above already moved every row into the new
-- partitioned table; this drops only the now-empty original, not live data)
DROP TABLE token_transfer_old;

-- Recreate every V1 index verbatim (as partitioned indexes — Postgres automatically attaches a
-- matching index to every existing and future partition), EXCEPT idx_transfer_event_type: see
-- the redundancy note below.
CREATE INDEX idx_transfer_asset      ON token_transfer (asset_id);
CREATE INDEX idx_transfer_deployment ON token_transfer (deployment_id);
CREATE INDEX idx_transfer_chain      ON token_transfer (chain_config_id, block_number DESC);
CREATE INDEX idx_transfer_contract   ON token_transfer (contract_address, occurred_at DESC);
CREATE INDEX idx_transfer_from       ON token_transfer (from_address);
CREATE INDEX idx_transfer_to         ON token_transfer (to_address);
-- idx_transfer_event_type (event_type alone) intentionally dropped: event_type only has 3
-- values (MINT/TRANSFER/BURN), no repository query filters on it in isolation (verified by
-- grepping TokenTransferRepository — every derived/native query filters by asset/deployment/
-- chain/contract/address instead), and a low-selectivity single-column btree index is even
-- less useful now that partition pruning already shrinks each query's working set to one
-- month's partition. idx_transfer_to is kept despite the same "not a derived-query filter"
-- reasoning because Dac8ExportService joins on to_address directly in native SQL.

-- Restore the canonical V1 constraint/index names now that they are free again.
ALTER TABLE token_transfer RENAME CONSTRAINT chk_event_type_new     TO chk_event_type;
ALTER TABLE token_transfer RENAME CONSTRAINT tt_pkey_new             TO token_transfer_pkey;
ALTER TABLE token_transfer RENAME CONSTRAINT uq_transfer_evm_new    TO uq_transfer_evm;
ALTER TABLE token_transfer RENAME CONSTRAINT uq_transfer_solana_new TO uq_transfer_solana;


-- ═══════════════════════════════════════════════════════════════════════════
-- 2b. blockchain_transaction → RANGE-partitioned on created_at
-- ═══════════════════════════════════════════════════════════════════════════

-- Same pattern as token_transfer above. blockchain_transaction has no unique constraints
-- besides its PK and no FK constraint anywhere in the schema points at it (verified the same
-- way as token_transfer), so the only required change is widening the PK to (id, created_at).
-- Column list is V1's CREATE TABLE (lines ~787-805) PLUS the ops_note/ops_reviewed_at/
-- ops_reviewed_by columns V1 adds later via ALTER TABLE (V1 line ~2689, "Schema changes
-- originally introduced in V5__blockchain_transaction_ops_note.sql" — folded into V1 by the
-- schema squash) — easy to miss by only reading the original CREATE TABLE block, so called out
-- explicitly here.
CREATE TABLE blockchain_transaction_new (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    tx_hash          VARCHAR(66),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    chain            VARCHAR(30),
    network          VARCHAR(30),
    contract_address VARCHAR(42),
    deployment_id    UUID,
    asset_id         UUID,
    method_name      VARCHAR(100),
    params           JSONB,
    actor_name       VARCHAR(255),
    actor_role       VARCHAR(30),
    gas_used         BIGINT,
    block_number     BIGINT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    ops_note         TEXT,
    ops_reviewed_at  TIMESTAMPTZ,
    ops_reviewed_by  UUID,
    CONSTRAINT bt_pkey_new PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE blockchain_transaction_new_default PARTITION OF blockchain_transaction_new DEFAULT;

ALTER TABLE blockchain_transaction RENAME TO blockchain_transaction_old;
ALTER TABLE blockchain_transaction_new RENAME TO blockchain_transaction;
ALTER TABLE blockchain_transaction_new_default RENAME TO blockchain_transaction_default;

DO $$
DECLARE
    earliest    DATE;
    latest_need DATE;
    month_start DATE;
    part_name   TEXT;
BEGIN
    SELECT COALESCE(date_trunc('month', MIN(created_at)), date_trunc('month', now()))::DATE
    INTO earliest
    FROM blockchain_transaction_old;

    latest_need := date_trunc('month', now() + INTERVAL '6 months')::DATE;

    month_start := earliest;
    WHILE month_start <= latest_need LOOP
        part_name := 'blockchain_transaction_' || to_char(month_start, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF blockchain_transaction FOR VALUES FROM (%L) TO (%L)',
            part_name, month_start, (month_start + INTERVAL '1 month')::DATE
        );
        month_start := (month_start + INTERVAL '1 month')::DATE;
    END LOOP;
END;
$$;

SELECT rw_ensure_monthly_partitions('blockchain_transaction', 'created_at', 6);

DO $$
DECLARE
    batch_size INT := 5000;
    moved      INT;
BEGIN
    LOOP
        INSERT INTO blockchain_transaction
            (id, tx_hash, status, chain, network, contract_address, deployment_id, asset_id,
             method_name, params, actor_name, actor_role, gas_used, block_number, error_message,
             created_at, completed_at, ops_note, ops_reviewed_at, ops_reviewed_by)
        SELECT o.id, o.tx_hash, o.status, o.chain, o.network, o.contract_address, o.deployment_id,
               o.asset_id, o.method_name, o.params, o.actor_name, o.actor_role, o.gas_used,
               o.block_number, o.error_message, o.created_at, o.completed_at, o.ops_note,
               o.ops_reviewed_at, o.ops_reviewed_by
        FROM blockchain_transaction_old o
        WHERE NOT EXISTS (SELECT 1 FROM blockchain_transaction n WHERE n.id = o.id)
        LIMIT batch_size;

        GET DIAGNOSTICS moved = ROW_COUNT;
        EXIT WHEN moved = 0;
    END LOOP;
END;
$$;

-- migration-safety: ack (batch-copy backfill above already moved every row into the new
-- partitioned table; this drops only the now-empty original, not live data)
DROP TABLE blockchain_transaction_old;

CREATE INDEX idx_btx_deployment ON blockchain_transaction (deployment_id);
CREATE INDEX idx_btx_asset      ON blockchain_transaction (asset_id);
CREATE INDEX idx_btx_actor      ON blockchain_transaction (actor_name);
CREATE INDEX idx_btx_status     ON blockchain_transaction (status) WHERE status = 'PENDING';
CREATE INDEX idx_btx_created    ON blockchain_transaction (created_at DESC);

ALTER TABLE blockchain_transaction RENAME CONSTRAINT bt_pkey_new TO blockchain_transaction_pkey;


-- ═══════════════════════════════════════════════════════════════════════════
-- 3. login_attempt — missing lookup index
-- ═══════════════════════════════════════════════════════════════════════════

-- LoginAttemptLimiter.isBlocked()/recordFailure() both look up by login_key with a WHERE on
-- updated_at; login_key is already the PRIMARY KEY (so point lookups by key alone are fine),
-- but RetentionSweepJob's age-based sweep (infrastructure/RetentionSweepJob.java) scans by
-- updated_at, which had no supporting index at all.
CREATE INDEX idx_login_attempt_updated_at ON login_attempt (updated_at);


-- ═══════════════════════════════════════════════════════════════════════════
-- 4. Dead Quartz job store — removed
-- ═══════════════════════════════════════════════════════════════════════════

-- CouponPaymentJob (corporateactions/internal) was written against org.quartz.Job but was
-- never registered anywhere: no JobDetail/Trigger bean, no SchedulerFactoryBean, no
-- spring.quartz.* configuration exists anywhere in the app (verified by grepping the whole
-- backend module for SchedulerFactoryBean/spring.quartz/org.quartz — CouponPaymentJob.java was
-- the only hit). It has been converted to a ShedLock'd @Scheduled bean (mirroring the sibling
-- BondMaturityJob) in the same commit as this migration, so these 10 qrtz_* job-store tables
-- (created by V1, never written to by any code path since Quartz's SchedulerFactoryBean was
-- never wired up) and the spring-boot-starter-quartz dependency are no longer needed.
-- migration-safety: ack (dead job-store tables, never written to by any wired-up scheduler —
-- verified above by grepping the whole backend module; no live data lost)
DROP TABLE IF EXISTS qrtz_simple_triggers;
DROP TABLE IF EXISTS qrtz_cron_triggers;
DROP TABLE IF EXISTS qrtz_blob_triggers;
DROP TABLE IF EXISTS qrtz_fired_triggers;
DROP TABLE IF EXISTS qrtz_paused_trigger_grps;
DROP TABLE IF EXISTS qrtz_scheduler_state;
DROP TABLE IF EXISTS qrtz_locks;
DROP TABLE IF EXISTS qrtz_calendars;
DROP TABLE IF EXISTS qrtz_triggers;
DROP TABLE IF EXISTS qrtz_job_details;
