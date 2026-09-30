-- K6 / Phase 4 (P4C-4, P4C-5)
--
-- P4C-5: operator wallet delete becomes a soft delete. The row is tombstoned and its (encrypted)
-- key material is kept until registerwerk.wallet.retention-days has passed; WalletPurgeJob then
-- destroys the key blob and hard-deletes the row. Within the window the deletion is reversible.
ALTER TABLE operator_wallet ADD COLUMN deleted_at          TIMESTAMPTZ;
ALTER TABLE operator_wallet ADD COLUMN deleted_by          UUID;
ALTER TABLE operator_wallet ADD COLUMN deleted_approver_id UUID;
CREATE INDEX idx_operator_wallet_deleted ON operator_wallet (deleted_at) WHERE deleted_at IS NOT NULL;

-- P4C-5: marks that a first wallet has ever existed. Only before this marker may a newly created
-- wallet be auto-promoted to chain default (fresh-install bootstrap); afterwards default switching
-- always goes through the 4-eyes setDefault path. Backfilled for instances that already have wallets.
CREATE TABLE wallet_bootstrap_marker (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO wallet_bootstrap_marker (id) SELECT 1 WHERE EXISTS (SELECT 1 FROM operator_wallet);

-- P4C-4: second approver and optional case reference of the request that submitted a chain tx.
ALTER TABLE blockchain_transaction ADD COLUMN approver_id     UUID;
ALTER TABLE blockchain_transaction ADD COLUMN case_reference  VARCHAR(200);
