-- T2-18: pledging/escrowing units into a pool contract (lending market, DvP escrow, desk,
-- facility) left a positive-balance wallet with no asset_holder row. HolderDataService refused to
-- reconcile (UnmappedHolderIdentityException) and HolderSyncScheduler only logged a WARN on every
-- run, so the register silently went stale and corporate-action snapshots read it anyway.
--
-- 1. The blocked-register state is persisted on the asset (operator banner + metric + alert).
-- 2. A pool contract gets a chain-derived NOMINEE_POOL holder row (investor = the operator's
--    legal entity), so the register reconciles again.
-- 3. Entitlements snapshotted for a NOMINEE_POOL row are HELD_LOOK_THROUGH and excluded from
--    settlement until the look-through question (PARK-T2-18) is decided.
-- 4. A corporate action whose record-date snapshot is refused becomes SNAPSHOT_BLOCKED (visible,
--    retried daily) instead of being snapshotted from a stale register.

ALTER TABLE asset ADD COLUMN holder_sync_status VARCHAR(16) NOT NULL DEFAULT 'OK';
ALTER TABLE asset ADD COLUMN holder_sync_blocked_reason TEXT;
-- Comma-separated, lower-cased wallet addresses that have a finalized positive balance but no
-- asset_holder row — what the operator must map (investor) or register (nominee pool).
ALTER TABLE asset ADD COLUMN holder_sync_unmapped_wallets TEXT;
ALTER TABLE asset ADD COLUMN last_successful_holder_sync_at TIMESTAMPTZ;
ALTER TABLE asset ADD CONSTRAINT ck_asset_holder_sync_status
    CHECK (holder_sync_status IN ('OK', 'BLOCKED'));

UPDATE asset SET last_successful_holder_sync_at = last_holder_sync_time
 WHERE last_holder_sync_time IS NOT NULL;

CREATE INDEX idx_asset_holder_sync_blocked ON asset (id) WHERE holder_sync_status = 'BLOCKED';

ALTER TABLE asset_holder ADD COLUMN holder_kind VARCHAR(20) NOT NULL DEFAULT 'INVESTOR';
ALTER TABLE asset_holder ADD CONSTRAINT ck_asset_holder_kind
    CHECK (holder_kind IN ('INVESTOR', 'NOMINEE_POOL'));

ALTER TABLE corporate_action_entry ADD COLUMN payout_status VARCHAR(24) NOT NULL DEFAULT 'PAYABLE';
ALTER TABLE corporate_action_entry ADD CONSTRAINT ck_ca_entry_payout_status
    CHECK (payout_status IN ('PAYABLE', 'HELD_LOOK_THROUGH'));

ALTER TABLE corporate_action ADD COLUMN snapshot_blocked_reason TEXT;

-- migration-safety: ack (CHECK constraint is re-created immediately below with one added value; no data is dropped)
ALTER TABLE corporate_action DROP CONSTRAINT ck_ca_status;
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_status CHECK (status IN (
    'PROPOSED', 'ANNOUNCED', 'SNAPSHOT_BLOCKED', 'RECORD_DATE_SET', 'COMPUTED', 'AWAITING_SETTLEMENT',
    'SETTLED', 'CLOSED', 'CANCELLED', 'REJECTED'
));
