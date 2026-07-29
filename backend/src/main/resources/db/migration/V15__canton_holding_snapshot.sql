-- Finding #1, Phase 10: CantonTransferSyncService's Archived-Holding handler previously had no
-- way to recover the consumed holding's real instrument/owner/amount — Daml's Archived ledger
-- event carries only the contract ID, not its former argument payload. This table is a durable
-- (restart-surviving) mirror of "currently open Holdings" the indexer has seen Created, keyed by
-- contract ID, so an Archived event can look up what it's actually consuming before it's removed.
CREATE TABLE canton_holding_snapshot (
    contract_id      VARCHAR(255) PRIMARY KEY,
    chain_config_id  UUID NOT NULL REFERENCES chain_config(id),
    instrument       VARCHAR(255) NOT NULL,
    owner            VARCHAR(255) NOT NULL,
    amount           NUMERIC(38,18) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_canton_holding_snapshot_chain ON canton_holding_snapshot (chain_config_id);
