-- Promotes chaincache to a first-class node kind rather than an indistinguishable direct-RPC URL
-- (see docs/plan "Preliminary-state awareness across the portfolio", Track C / P9). A
-- CHAINCACHE-kind rpc_node additionally records the base chaincache instance URL
-- (management_url, used for the GET /api/capabilities probe and the durable-stream WebSocket) and
-- which of chaincache's own multi-chain keys (remote_chain_key) this ChainConfig maps to, plus the
-- capabilities chaincache last reported (capabilities, JSONB — refreshed on add and by a periodic
-- probe, not authoritative between probes).

ALTER TABLE rpc_node ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DIRECT_RPC';
ALTER TABLE rpc_node ADD COLUMN management_url VARCHAR(512);
ALTER TABLE rpc_node ADD COLUMN remote_chain_key VARCHAR(80);
ALTER TABLE rpc_node ADD COLUMN capabilities JSONB;

-- finality_source lets a chain opt into chaincache's push-based, gap-free durable retraction
-- stream instead of this registry's own poll-based safe/finalized-tag probing — see
-- blockchain.internal.ChaincacheDurableStreamManager. Defaults to the existing self-probe
-- behavior so every pre-existing chain is unaffected until an operator explicitly opts in.
ALTER TABLE chain_config ADD COLUMN finality_source VARCHAR(20) NOT NULL DEFAULT 'RPC_SELF_PROBE';
