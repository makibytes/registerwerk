-- V3: RPC node health monitoring
-- Replaces the single rpc_url / fallback_rpc_urls pattern with a proper rpc_node table
-- that tracks health, block advancement, and manual enable/disable state per endpoint.

CREATE TABLE rpc_node (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chain_config_id        UUID        NOT NULL REFERENCES chain_config(id) ON DELETE CASCADE,
    url                    VARCHAR(512) NOT NULL,
    label                  VARCHAR(100),
    enabled                BOOLEAN     NOT NULL DEFAULT true,   -- false = operator manually stopped it
    exclusive              BOOLEAN     NOT NULL DEFAULT false,  -- if any node in chain is exclusive, only those are used
    latest_block_number    BIGINT,
    block_last_advanced_at TIMESTAMPTZ,                        -- when block_number last increased
    last_checked_at        TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    healthy                BOOLEAN     NOT NULL DEFAULT false,
    consecutive_failures   INT         NOT NULL DEFAULT 0,
    lag_from_best          INT,                                 -- blocks behind best known for this chain
    syncing                BOOLEAN     NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rpc_node_chain  ON rpc_node (chain_config_id);
CREATE INDEX idx_rpc_node_usable ON rpc_node (chain_config_id, enabled, healthy);

-- Seed initial nodes from existing chain_config primary RPC URLs
INSERT INTO rpc_node (chain_config_id, url, label, enabled)
SELECT id, rpc_url, 'Primary', true
FROM chain_config
WHERE rpc_url IS NOT NULL AND rpc_url <> '';
