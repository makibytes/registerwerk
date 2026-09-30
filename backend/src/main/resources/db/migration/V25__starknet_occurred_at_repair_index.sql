-- StarknetOccurredAtRepair looks for rows that still carry a poll-time occurred_at (no raw_data.blockTimestamp
-- marker). Without an index that scan walked every transfer row every 10 minutes; this partial index holds
-- only the still-unrepaired rows, so the scan is bounded by the remaining work and is empty once done.
CREATE INDEX IF NOT EXISTS idx_transfer_occurred_at_unrepaired
    ON token_transfer (chain_config_id, block_number)
    WHERE block_number IS NOT NULL AND (raw_data IS NULL OR raw_data->>'blockTimestamp' IS NULL);
