-- Phase 4 (P4-01, P4-03, P4-06).

-- P4-01: a holder whose deployment has no indexed rows at all used to be compared against its own
-- register balance (COALESCE) and therefore never drifted. The drift job now records that case as
-- an explicit NOT_INDEXED event instead of pretending the chain agrees with the register.
ALTER TABLE chain_drift_event
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DRIFT',
    ADD CONSTRAINT chk_drift_kind CHECK (kind IN ('DRIFT', 'NOT_INDEXED'));

-- P4-03: drift is now one event per (asset, wallet) across all CONFIRMED deployments, no longer one
-- per (deployment, wallet). Older per-deployment OPEN duplicates for the same asset and wallet are
-- superseded (the newest stays open and is refreshed by the job).
UPDATE chain_drift_event e
SET status = 'RESOLVED', resolved_at = now(),
    resolution_notes = 'Superseded: drift is aggregated per (asset, wallet) across deployments since Phase 4.'
WHERE e.status = 'OPEN'
  AND EXISTS (SELECT 1 FROM chain_drift_event n
              WHERE n.status = 'OPEN' AND n.asset_id = e.asset_id
                AND lower(n.wallet_address) = lower(e.wallet_address)
                AND (n.detected_at, n.id) > (e.detected_at, e.id));

-- P4-06: transfers indexed before their deployment row existed have deployment_id NULL and are
-- linked afterwards by a repair pass keyed on (chain, address).
CREATE INDEX idx_transfer_unlinked ON token_transfer (chain_config_id, lower(contract_address))
    WHERE deployment_id IS NULL;
