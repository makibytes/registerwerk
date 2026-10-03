-- Wave 0b H7: the corporate-action freshness gate compared the WALL-CLOCK time of the last holder sync with the
-- record-date cut-off. A lagging indexer (or a sync that ran against stale data) passed it although the chain had
-- never been indexed past the cut-off. The indexer now records the BLOCK TIME of the head block it has processed;
-- the gate compares that with the cut-off.
ALTER TABLE indexer_state ADD COLUMN last_synced_block_time TIMESTAMPTZ;
COMMENT ON COLUMN indexer_state.last_synced_block_time IS
    'Block timestamp of last_synced_block (chain time, not wall-clock); NULL when the indexer cannot report it.';
