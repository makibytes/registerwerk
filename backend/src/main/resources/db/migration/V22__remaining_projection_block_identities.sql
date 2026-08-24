-- Compensation must only undo the exact block occurrence that produced the current projection.
-- A height (or transaction hash) can be reused when a transaction is re-mined in a replacement
-- block, so the causal block hash is persisted beside every remaining reversible projection.

ALTER TABLE vault_nav_strike ADD COLUMN block_hash VARCHAR(128);
ALTER TABLE vault_request ADD COLUMN block_hash VARCHAR(128);
ALTER TABLE asset_vault_state ADD COLUMN deposit_cap_block_hash VARCHAR(128);

ALTER TABLE dapp_version ADD COLUMN anchor_chain_config_id UUID REFERENCES chain_config(id);
ALTER TABLE dapp_version ADD COLUMN anchor_block_number BIGINT;
ALTER TABLE dapp_version ADD COLUMN anchor_block_hash VARCHAR(128);
