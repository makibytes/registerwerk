-- Wires the FinalityGate freeze check V5's comment already anticipated ("the FinalityGate freeze
-- check, a later phase") but was never actually implemented: FinalityGateImpl did not consult
-- chain_effect at all, so an unresolved (failed or irreversible) compensation never blocked
-- further operations on the affected asset, contrary to the portfolio plan's documented design
-- ("the gate tightens: while any chain_effect for asset X is unresolved, FinalityGate blocks every
-- operation on X until an admin acknowledges with a reason").
--
-- asset_id is denormalised onto chain_effect (rather than resolved via entity_type/entity_id at
-- gate-check time) because the finality module deliberately imports nothing but shared and
-- audit.api — resolving entity_id back to an asset would require importing asset/deployment/
-- vault/etc. Populated only where the recording module already has an assetId at hand (asset
-- deployments, vault strikes/requests, blockchain transactions); left null for effect types that
-- are not asset-scoped (org identity, ecosystem permissions, marketplace listings) — no
-- GatedOperation gates those today, so this is not a coverage gap in practice.
ALTER TABLE chain_effect ADD COLUMN asset_id UUID;

-- The reason an admin gave when unblocking an asset frozen by an unresolved compensation —
-- acknowledged_by/acknowledged_at already existed (V5) but had no accompanying reason column,
-- unlike every other break-glass action in this codebase (e.g. finality_policy_override.reason).
ALTER TABLE chain_effect ADD COLUMN acknowledge_reason TEXT;

-- The gate's hot query: "does asset X have any unresolved (failed/irreversible, unacknowledged)
-- compensation". Partial index — only COMPENSATION_FAILED/IRREVERSIBLE_ESCALATED rows are ever
-- looked up this way, and most rows never reach those statuses.
CREATE INDEX idx_chain_effect_asset_unresolved ON chain_effect (asset_id)
    WHERE asset_id IS NOT NULL
      AND status IN ('COMPENSATION_FAILED', 'IRREVERSIBLE_ESCALATED')
      AND acknowledged_at IS NULL;
