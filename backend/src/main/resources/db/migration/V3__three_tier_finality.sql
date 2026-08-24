-- Widens token_transfer.finality_status from the two-tier PROVISIONAL/FINAL/ORPHANED model to the
-- three-tier PROVISIONAL/SAFE/FINALIZED/ORPHANED model (FinalityLevel), aligning with chaincache's
-- BlockFinality vocabulary. FINAL becomes FINALIZED (a rename, not a demotion — chains that were
-- already writing FINAL directly, or reorg-promoting to it, keep exactly the same authoritative
-- meaning under the new name); no existing row is ever mapped to SAFE, since nothing in this
-- registry has ever computed that level before this migration. Also adds the two columns the
-- follow-on finality-gate/holder-reconciliation work needs.

-- migration-safety: ack (constraint replacement only, immediately re-added below with the widened
-- value set covering every value the old constraint allowed plus SAFE; no column or row is
-- dropped, and dropping/re-adding a CHECK constraint on a partitioned table's parent cascades to
-- every existing and future partition automatically)
ALTER TABLE token_transfer DROP CONSTRAINT chk_transfer_finality;

UPDATE token_transfer SET finality_status = 'FINALIZED' WHERE finality_status = 'FINAL';

ALTER TABLE token_transfer ALTER COLUMN finality_status TYPE VARCHAR(16);
ALTER TABLE token_transfer ALTER COLUMN finality_status SET DEFAULT 'FINALIZED';

ALTER TABLE token_transfer ADD CONSTRAINT chk_transfer_finality
    CHECK (finality_status IN ('PROVISIONAL', 'SAFE', 'FINALIZED', 'ORPHANED'));

-- The old predicate (`<> 'FINAL'`) would now match every row post-rename (no row is ever literally
-- 'FINAL' again), silently turning this from a partial index covering only the small
-- still-unsettled window into a full index covering the whole table. Rebuilt against the current
-- terminal value.
DROP INDEX idx_transfer_finality;
CREATE INDEX idx_transfer_finality ON token_transfer (chain_config_id, finality_status, block_number)
    WHERE finality_status <> 'FINALIZED';

-- Feeds the finality gate's "estimated time until this level is reached" — null (the default)
-- means "unknown", never a guessed number; populated per chain by an operator, not derived here.
ALTER TABLE chain_config ADD COLUMN avg_block_seconds INT;

-- Distinguishes an off-chain register entry (manually maintained, chain not authoritative for it)
-- from an on-chain holder whose wallet has, on a later resync, vanished from the counted set
-- entirely — the two are otherwise indistinguishable to HolderDataService.syncHoldersFromBlockchain,
-- which needs the flag to zero the latter without ever touching the former. Set by application code
-- in a follow-on change; every existing row defaults to false (conservatively "not chain-derived",
-- i.e. left untouched) until that code starts marking rows true going forward.
ALTER TABLE asset_holder ADD COLUMN chain_derived BOOLEAN NOT NULL DEFAULT false;
