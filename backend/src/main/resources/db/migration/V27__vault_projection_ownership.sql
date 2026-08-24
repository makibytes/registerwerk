-- Denormalized vault projections need an unambiguous owner. Business effective_at is not unique,
-- so it cannot identify which NAV strike may be compensated after a reorg.
ALTER TABLE asset_vault_state
    ADD COLUMN latest_nav_strike_id UUID REFERENCES vault_nav_strike(id);

-- Backfill only projections that exactly match a successfully confirmed strike. Highest strike_id
-- wins when historical duplicate business timestamps exist.
UPDATE asset_vault_state state
SET latest_nav_strike_id = (
    SELECT strike.id
    FROM vault_nav_strike strike
    WHERE strike.asset_id = state.asset_id
      AND strike.confirmed = true
      AND strike.chain_config_id IS NOT NULL
      AND strike.block_number IS NOT NULL
      AND strike.block_hash IS NOT NULL
      AND strike.effective_at = state.latest_nav_strike_at
      AND strike.nav_per_share = state.latest_nav_per_share
      AND strike.report_hash IS NOT DISTINCT FROM state.latest_nav_report_hash
    ORDER BY strike.strike_id DESC
    LIMIT 1
)
WHERE state.latest_nav_strike_at IS NOT NULL;

-- Preserve the transaction provenance of the current deposit-cap projection. This is separate
-- from deposit_cap_tx_hash, which intentionally represents only an in-flight transaction.
ALTER TABLE asset_vault_state
    ADD COLUMN deposit_cap_confirmed_tx_hash VARCHAR(80);

-- Recover transaction ownership where the pre-migration projection already had an exact block
-- identity and its corresponding finalized effect is available.  Height/hash without the
-- confirming transaction is not sufficient ownership: the same cap value may be written by
-- several transactions and the compensator must only undo the current occurrence.
UPDATE asset_vault_state state
SET deposit_cap_confirmed_tx_hash = (
    SELECT effect.tx_hash
    FROM chain_effect effect
    WHERE effect.entity_id = state.asset_id
      AND effect.effect_type = 'VAULT_DEPOSIT_CAP_CONFIRMED'
      AND effect.chain_config_id = state.deposit_cap_chain_config_id
      AND effect.block_number = state.deposit_cap_block_number
      AND effect.block_hash = state.deposit_cap_block_hash
      AND effect.tx_hash IS NOT NULL
    ORDER BY effect.journal_sequence DESC
    LIMIT 1
)
WHERE state.deposit_cap_chain_config_id IS NOT NULL
  AND state.deposit_cap_block_number IS NOT NULL
  AND state.deposit_cap_block_hash IS NOT NULL;

-- Older rows can contain a height without an exact hash because the original listener predated
-- occurrence-aware finality. Keep their numeric cap as the baseline, but do not pretend that its
-- incomplete provenance can participate in exact compensation.
UPDATE asset_vault_state
SET deposit_cap_chain_config_id = NULL,
    deposit_cap_block_number = NULL,
    deposit_cap_block_hash = NULL,
    deposit_cap_confirmed_tx_hash = NULL
WHERE (deposit_cap_chain_config_id IS NULL)
   <> (deposit_cap_block_number IS NULL)
   OR (deposit_cap_chain_config_id IS NULL)
   <> (deposit_cap_block_hash IS NULL)
   OR (deposit_cap_chain_config_id IS NOT NULL
       AND deposit_cap_confirmed_tx_hash IS NULL);

ALTER TABLE asset_vault_state
    ADD CONSTRAINT chk_deposit_cap_complete_block_identity CHECK (
        (deposit_cap_chain_config_id IS NULL
            AND deposit_cap_block_number IS NULL
            AND deposit_cap_block_hash IS NULL
            AND deposit_cap_confirmed_tx_hash IS NULL)
        OR
        (deposit_cap_chain_config_id IS NOT NULL
            AND deposit_cap_block_number IS NOT NULL
            AND deposit_cap_block_hash IS NOT NULL
            AND deposit_cap_confirmed_tx_hash IS NOT NULL)
    );
