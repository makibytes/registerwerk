-- Add Fhenix and Inco chains for confidential ERC-3643 (Zama fhEVM)
INSERT INTO chain_config (identifier, display_name, chain_type, network_type, chain_id,
                          rpc_url, ws_url, block_explorer_url,
                          graph_node_url, graph_subgraph_name) VALUES

-- Fhenix — FHE L2 on Ethereum
('FHENIX_MAINNET', 'Fhenix Mainnet', 'EVM', 'MAINNET', 21888,
 'https://api.fhenix.zone:7747',
 'wss://api.fhenix.zone:7748',
 'https://explorer.fhenix.zone',
 NULL, NULL),

('FHENIX_HELIUM', 'Fhenix Helium Testnet', 'EVM', 'TESTNET', 8008135,
 'https://api.helium.fhenix.zone:7747',
 'wss://api.helium.fhenix.zone:7748',
 'https://explorer.helium.fhenix.zone',
 NULL, NULL),

-- Inco — Confidentiality-as-a-Service
('INCO_MAINNET', 'Inco Mainnet', 'EVM', 'MAINNET', 9090,
 'https://mainnet.inco.org',
 'wss://mainnet.inco.org',
 'https://explorer.inco.org',
 NULL, NULL),

('INCO_RIVEST', 'Inco Rivest Testnet', 'EVM', 'TESTNET', 21097,
 'https://validator.rivest.inco.org',
 'wss://validator.rivest.inco.org',
 'https://explorer.rivest.inco.org',
 NULL, NULL);
