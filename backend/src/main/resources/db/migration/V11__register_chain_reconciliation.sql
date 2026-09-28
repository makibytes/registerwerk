-- Phase 3 / K2: register <-> chain reconciliation.
--
-- T3-17: a removed (soft-deleted) holder row kept its (asset_id, wallet_address) slot in the
-- unique index, so the wallet could never re-enter the register, and the holder sync matched the
-- removed row and wrote on-chain balances onto a closed register entry. Uniqueness now applies to
-- active rows only; removed rows stay as history. HolderDataService keys active and removed rows
-- separately in the same release (a wallet may now have one active row plus any number of
-- removed rows).
-- The index is recreated immediately below as a partial unique index; no data is dropped.
DROP INDEX IF EXISTS idx_holder_wallet;
CREATE UNIQUE INDEX idx_holder_wallet ON asset_holder (asset_id, wallet_address) WHERE removed_at IS NULL;

-- T3-09: number of active, non-chain-derived register entries with a positive nominal on an asset
-- that has deployments. Such rows are not backed by the chain (e.g. off-chain trade settlement,
-- manual entries); the holder sync reports them instead of blocking. Written only by
-- HolderSyncStatusPortImpl bulk updates, like the other holder_sync_* columns.
ALTER TABLE asset ADD COLUMN holder_sync_offchain_rows INTEGER NOT NULL DEFAULT 0;

-- T3-17: the portfolio-migration handover is now verified against indexed chain data. Where no
-- indexed deployment exists (off-chain register, Solana/Canton until Phase 4) the operator records
-- an attestation instead (the endpoint is already step-up + 4-eyes).
ALTER TABLE portfolio_migration_request ADD COLUMN operator_attestation TEXT;
-- T3-07 (C-05b): consent reference of the beneficiary of third-party rights / disposal
-- restrictions on the migrated entry; required when those §17(2) attributes are set.
ALTER TABLE portfolio_migration_request ADD COLUMN beneficiary_consent_ref TEXT;
