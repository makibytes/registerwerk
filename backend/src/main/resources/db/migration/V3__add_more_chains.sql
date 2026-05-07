-- Add EVM L2 chains (Arbitrum, Avalanche, Optimism) and non-EVM stubs
-- (Starknet, Stellar, Canton) plus new token standards (SPL_2022,
-- STARKNET_ERC20, STELLAR_ASSET, CANTON_TOKEN).
--
-- EVM L2 chain_config rows are enabled=true; non-EVM stubs are enabled=false
-- so they don't enter the existing EVM/Solana health-check and indexer paths.

-- ── 1. Widen CHECK constraints ───────────────────────────────────────────────

ALTER TABLE asset
  DROP   CONSTRAINT chk_token_standard,
  ADD    CONSTRAINT chk_token_standard CHECK (
    token_standard IN (
      'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
      'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN'
    )
  );

ALTER TABLE asset_deployment
  DROP   CONSTRAINT chk_chain,
  ADD    CONSTRAINT chk_chain CHECK (
    chain IN (
      'ETHEREUM','POLYGON','BASE','SOLANA',
      'ARBITRUM','AVALANCHE','OPTIMISM',
      'STARKNET','STELLAR','CANTON'
    )
  );

ALTER TABLE chain_config
  DROP   CONSTRAINT chk_chain_type,
  ADD    CONSTRAINT chk_chain_type CHECK (
    chain_type IN ('EVM','SOLANA','STARKNET','STELLAR','CANTON')
  );

ALTER TABLE trade_listing
  DROP   CONSTRAINT chk_trade_listing_token_standard,
  ADD    CONSTRAINT chk_trade_listing_token_standard CHECK (
    token_standard IN (
      'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
      'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN'
    )
  ),
  DROP   CONSTRAINT chk_trade_listing_chain,
  ADD    CONSTRAINT chk_trade_listing_chain CHECK (
    chain IS NULL OR chain IN (
      'ETHEREUM','POLYGON','BASE','SOLANA',
      'ARBITRUM','AVALANCHE','OPTIMISM',
      'STARKNET','STELLAR','CANTON'
    )
  );

ALTER TABLE trade_execution
  DROP   CONSTRAINT chk_trade_execution_token_standard,
  ADD    CONSTRAINT chk_trade_execution_token_standard CHECK (
    token_standard IN (
      'ERC20','ERC721','ERC1155','ERC3643','CONF_ERC20','CONF_ERC3643',
      'SPL','SPL_2022','STARKNET_ERC20','STELLAR_ASSET','CANTON_TOKEN'
    )
  ),
  DROP   CONSTRAINT chk_trade_execution_chain,
  ADD    CONSTRAINT chk_trade_execution_chain CHECK (
    chain IS NULL OR chain IN (
      'ETHEREUM','POLYGON','BASE','SOLANA',
      'ARBITRUM','AVALANCHE','OPTIMISM',
      'STARKNET','STELLAR','CANTON'
    )
  );

-- ── 2. EVM L2 chain_config rows (enabled = true) ─────────────────────────────

INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES

('ARBITRUM_MAINNET', 'Arbitrum One', 'EVM', 'MAINNET', 42161,
 'https://arbitrum.publicnode.com', 'wss://arbitrum-one.publicnode.com', 'https://arbiscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-mainnet'),

('ARBITRUM_SEPOLIA', 'Arbitrum Sepolia', 'EVM', 'TESTNET', 421614,
 'https://sepolia-rollup.arbitrum.io/rpc', 'wss://sepolia-rollup.arbitrum.io/ws', 'https://sepolia.arbiscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/arbitrum-sepolia'),

('AVALANCHE_MAINNET', 'Avalanche C-Chain', 'EVM', 'MAINNET', 43114,
 'https://api.avax.network/ext/bc/C/rpc', 'wss://api.avax.network/ext/bc/C/ws', 'https://snowtrace.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-mainnet'),

('AVALANCHE_FUJI', 'Avalanche Fuji Testnet', 'EVM', 'TESTNET', 43113,
 'https://api.avax-test.network/ext/bc/C/rpc', 'wss://api.avax-test.network/ext/bc/C/ws', 'https://testnet.snowtrace.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/avalanche-fuji'),

('OPTIMISM_MAINNET', 'Optimism', 'EVM', 'MAINNET', 10,
 'https://mainnet.optimism.io', 'wss://ws-mainnet.optimism.io', 'https://optimistic.etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-mainnet'),

('OPTIMISM_SEPOLIA', 'Optimism Sepolia', 'EVM', 'TESTNET', 11155420,
 'https://sepolia.optimism.io', 'wss://sepolia.optimism.io', 'https://sepolia-optimism.etherscan.io',
 'http://graph-node:8000/subgraphs/name', 'registerwerk/optimism-sepolia');

-- ── 3. Non-EVM stub chain_config rows (enabled = false) ─────────────────────
-- These chains are registered for display and tracking only.
-- Flip enabled=true once the corresponding client integration is implemented.

INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url, enabled) VALUES

('STARKNET_MAINNET', 'Starknet Mainnet', 'STARKNET', 'MAINNET', NULL,
 'https://rpc.starknet.lava.build', NULL, 'https://starkscan.co', false),

('STARKNET_SEPOLIA', 'Starknet Sepolia', 'STARKNET', 'TESTNET', NULL,
 'https://api.cartridge.gg/x/starknet/sepolia', NULL, 'https://sepolia.starkscan.co', false),

('STELLAR_MAINNET', 'Stellar Mainnet', 'STELLAR', 'MAINNET', NULL,
 'https://horizon.stellar.org', NULL, 'https://stellar.expert/explorer/public', false),

('STELLAR_TESTNET', 'Stellar Testnet', 'STELLAR', 'TESTNET', NULL,
 'https://horizon-testnet.stellar.org', NULL, 'https://stellar.expert/explorer/testnet', false),

('CANTON_MAINNET', 'Canton Mainnet', 'CANTON', 'MAINNET', NULL,
 '', NULL, 'https://canton.network', false),

('CANTON_DEVNET', 'Canton DevNet', 'CANTON', 'TESTNET', NULL,
 '', NULL, 'https://canton.network', false);

-- ── 4. Auto-seed Primary rpc_node from rpc_url (EVM L2s only) ───────────────

INSERT INTO rpc_node (chain_config_id, url, label, enabled)
SELECT id, rpc_url, 'Primary', true
FROM chain_config
WHERE identifier IN ('ARBITRUM_MAINNET','ARBITRUM_SEPOLIA',
                     'AVALANCHE_MAINNET','AVALANCHE_FUJI',
                     'OPTIMISM_MAINNET','OPTIMISM_SEPOLIA')
  AND rpc_url IS NOT NULL AND rpc_url <> '';
