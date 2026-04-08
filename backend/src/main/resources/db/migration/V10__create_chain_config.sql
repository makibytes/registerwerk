-- Dynamic chain registry — admin adds new chains here without code changes.
-- The BlockchainClientRegistry reads this table at startup and on refresh.

CREATE TABLE chain_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier          VARCHAR(50)  NOT NULL UNIQUE,   -- e.g. 'ETHEREUM_MAINNET', 'ARBITRUM_MAINNET'
    display_name        VARCHAR(100) NOT NULL,
    chain_type          VARCHAR(10)  NOT NULL,           -- 'EVM' | 'SOLANA'
    network_type        VARCHAR(10)  NOT NULL,           -- 'MAINNET' | 'TESTNET'
    chain_id            BIGINT,                          -- EVM chain-id; NULL for Solana
    rpc_url             VARCHAR(500) NOT NULL,
    ws_url              VARCHAR(500),                    -- WebSocket RPC (optional but recommended)
    fallback_rpc_urls   TEXT[],                          -- additional RPC endpoints for failover
    block_explorer_url  VARCHAR(200),                    -- e.g. 'https://etherscan.io' (no trailing slash)
    graph_node_url      VARCHAR(300),                    -- graph-node HTTP endpoint for this chain
    graph_subgraph_name VARCHAR(100),                    -- deployed subgraph name on graph-node
    enabled             BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_chain_type   CHECK (chain_type   IN ('EVM', 'SOLANA')),
    CONSTRAINT chk_network_type CHECK (network_type IN ('MAINNET', 'TESTNET'))
);

CREATE INDEX idx_chain_config_type    ON chain_config (chain_type, network_type);
CREATE INDEX idx_chain_config_enabled ON chain_config (enabled);

-- ── Seed: pre-configure the chains from the original spec ───────────────────
-- RPC URLs use public defaults; update via admin UI or environment-specific migration.
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES

-- Ethereum
('ETHEREUM_MAINNET', 'Ethereum Mainnet', 'EVM', 'MAINNET', 1,
 'https://mainnet.infura.io/v3/changeme', 'wss://mainnet.infura.io/ws/v3/changeme', 'https://etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-mainnet'),

('ETHEREUM_SEPOLIA', 'Ethereum Sepolia', 'EVM', 'TESTNET', 11155111,
 'https://sepolia.infura.io/v3/changeme', 'wss://sepolia.infura.io/ws/v3/changeme', 'https://sepolia.etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/ethereum-sepolia'),

-- Polygon
('POLYGON_MAINNET', 'Polygon Mainnet', 'EVM', 'MAINNET', 137,
 'https://polygon-mainnet.infura.io/v3/changeme', 'wss://polygon-mainnet.infura.io/ws/v3/changeme', 'https://polygonscan.com',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-mainnet'),

('POLYGON_AMOY', 'Polygon Amoy Testnet', 'EVM', 'TESTNET', 80002,
 'https://polygon-amoy.infura.io/v3/changeme', 'wss://polygon-amoy.infura.io/ws/v3/changeme', 'https://amoy.polygonscan.com',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/polygon-amoy'),

-- Base
('BASE_MAINNET', 'Base Mainnet', 'EVM', 'MAINNET', 8453,
 'https://mainnet.base.org', 'wss://mainnet.base.org', 'https://basescan.org',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-mainnet'),

('BASE_SEPOLIA', 'Base Sepolia Testnet', 'EVM', 'TESTNET', 84532,
 'https://sepolia.base.org', 'wss://sepolia.base.org', 'https://sepolia.basescan.org',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/base-sepolia'),

-- Solana
('SOLANA_MAINNET', 'Solana Mainnet Beta', 'SOLANA', 'MAINNET', NULL,
 'https://api.mainnet-beta.solana.com', 'wss://api.mainnet-beta.solana.com', 'https://solscan.io',
 NULL, NULL),

('SOLANA_DEVNET', 'Solana Devnet', 'SOLANA', 'TESTNET', NULL,
 'https://api.devnet.solana.com', 'wss://api.devnet.solana.com', 'https://solscan.io',
 NULL, NULL);
