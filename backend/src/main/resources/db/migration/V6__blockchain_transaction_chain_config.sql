-- Links blockchain_transaction back to chain_config so a reorg retraction (chainConfigId +
-- forkBlockNumber) can find affected rows without going through chain/network string matching.
-- Nullable: existing rows predate this column and simply won't be discoverable by the
-- compensation sweep, which is an acceptable gap for historical rows on a reference
-- implementation (see BlockchainTransactionService.record's javadoc for how new rows populate it).

ALTER TABLE blockchain_transaction ADD COLUMN chain_config_id UUID REFERENCES chain_config(id);

CREATE INDEX idx_blockchain_transaction_chain_config_block
    ON blockchain_transaction (chain_config_id, block_number);
