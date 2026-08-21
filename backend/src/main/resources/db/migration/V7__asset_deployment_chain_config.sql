-- Mirrors V6's blockchain_transaction.chain_config_id: lets the reorg-retraction sweep
-- (finality.internal.BlockFinalityServiceImpl#recordRetraction) find affected asset_deployment
-- rows by (chainConfigId, blockNumber) instead of resolving chain/network enums at query time.
-- Nullable: existing rows predate this column.

ALTER TABLE asset_deployment ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);

CREATE INDEX idx_asset_deployment_chain_config_block
    ON asset_deployment (chain_config_id, block_number);
