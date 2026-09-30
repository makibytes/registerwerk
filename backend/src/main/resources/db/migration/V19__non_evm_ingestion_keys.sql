-- Phase 4 K2 (P4-02 / P4-08 / P4D-3 / P4D-5): non-EVM ingestion.
-- This is the only migration that changes token_transfer uniqueness in Phase 4.

-- 1) uq_transfer_solana was (chain, tx_hash, slot, occurred_at) with NULLS NOT DISTINCT. It admitted
--    exactly one row per signature (Solana) or per update id (Canton), and - because slot is NULL for
--    every EVM row - one row per transaction for EVM/Starknet/Stellar too, so a second Transfer log
--    or a second balance change in the same transaction violated it and aborted the batch.
--    Rows of one transaction are now distinguished by their position (log_index) and emitting contract
--    (Starknet: two watched tokens can emit at the same position of one transaction).
-- migration-safety: ack (constraint is re-created immediately below with a strictly weaker key; no row can violate it)
ALTER TABLE token_transfer DROP CONSTRAINT uq_transfer_solana;
ALTER TABLE token_transfer ADD CONSTRAINT uq_transfer_solana
    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, slot, log_index, contract_address, occurred_at);

-- 2) uq_transfer_evm omitted the emitting contract: two watched tokens emitting a Transfer at the same
--    log position of one transaction (Starknet, whose event position is derived per contract) collided
--    and the second row was lost as a "duplicate". The contract joins the key (superset of the old key).
-- migration-safety: ack (constraint is re-created immediately below with a strictly weaker key; no row can violate it)
ALTER TABLE token_transfer DROP CONSTRAINT uq_transfer_evm;
ALTER TABLE token_transfer ADD CONSTRAINT uq_transfer_evm
    UNIQUE NULLS NOT DISTINCT (chain_config_id, tx_hash, log_index, block_hash, contract_address, occurred_at);

-- 3) Per-deployment ingestion cursor (Stellar Stage 1). Horizon's operation feed was read with one
--    chain-wide minimum cursor; a deployment added later was only scanned from that shared position.
--    Each deployment now owns its cursor, so it is scanned from its own beginning.
CREATE TABLE indexer_deployment_cursor (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id   UUID NOT NULL REFERENCES asset_deployment(id),
    indexer_type    VARCHAR(30) NOT NULL,
    cursor_value    VARCHAR(200),
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_indexer_deployment_cursor UNIQUE (deployment_id, indexer_type),
    CONSTRAINT chk_indexer_deployment_cursor_type CHECK (indexer_type IN ('STELLAR_HORIZON', 'CANTON_STREAM', 'SOLANA_POLL'))
);

-- 4) Repairs that are safe to do in SQL.
--    Canton rows were always written with deployment_id NULL. Link those whose instrument string is
--    exactly a Canton deployment's contract address on the same chain; everything else stays
--    unlinked (see the phase-4 ledger: pre-K2 Canton rows also contain phantom MINT/BURN pairs from
--    split/merge and zero-amount "unknown holding" burns; they are evidence and are not rewritten).
UPDATE token_transfer tt
   SET deployment_id = ad.id, asset_id = ad.asset_id
  FROM asset_deployment ad, chain_config cc
 WHERE tt.deployment_id IS NULL
   AND cc.id = tt.chain_config_id AND cc.chain_type = 'CANTON'
   AND ad.chain_config_id = tt.chain_config_id
   AND ad.contract_address = tt.contract_address
   AND ad.deployment_status <> 'FAILED';

--    Starknet rows written before K2 may have lost same-transaction events of a second watched token
--    (the dedup key had no contract). Rewinding the Starknet cursor makes the next pass re-read the
--    chain history; existing rows are recognised by the widened key, missing ones are inserted.
UPDATE indexer_state
   SET last_synced_block = NULL, updated_at = now()
 WHERE indexer_type = 'STARKNET_POLL'
   AND last_synced_block IS NOT NULL;
