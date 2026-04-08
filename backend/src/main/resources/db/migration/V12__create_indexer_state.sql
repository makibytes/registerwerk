-- Tracks the sync cursor per chain/indexer-type.
-- On restart the sync services read last_synced_block / last_synced_signature
-- and replay from there — ensuring no events are missed even after days of downtime.

CREATE TABLE indexer_state (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_config_id          UUID        NOT NULL REFERENCES chain_config(id),
    indexer_type             VARCHAR(30) NOT NULL,   -- 'GRAPH_NODE' | 'SOLANA_GEYSER' | 'SOLANA_POLL'
    last_synced_block        BIGINT,                 -- EVM block number / Solana slot
    last_synced_signature    VARCHAR(100),           -- Solana last signature
    last_synced_at           TIMESTAMPTZ,
    last_error               TEXT,
    consecutive_errors       INT         NOT NULL DEFAULT 0,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_indexer_state UNIQUE (chain_config_id, indexer_type),
    CONSTRAINT chk_indexer_type CHECK (indexer_type IN ('GRAPH_NODE', 'SOLANA_GEYSER', 'SOLANA_POLL')),
    CONSTRAINT chk_indexer_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR'))
);

CREATE INDEX idx_indexer_state_chain  ON indexer_state (chain_config_id);
CREATE INDEX idx_indexer_state_status ON indexer_state (status);
