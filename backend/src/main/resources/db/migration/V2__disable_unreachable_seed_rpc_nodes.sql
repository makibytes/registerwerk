-- V2 — stop the demo stack probing RPC endpoints that can never answer.
--
-- V1 seeds chain_config with public defaults and then derives one rpc_node row per chain
-- (V1__initial_schema.sql:2302-2305). Several of those endpoints are placeholders or dead
-- networks, so a stock `docker compose up` had RpcNodeHealthService issuing two blocking
-- JSON-RPC calls per node every 30 seconds against hosts that always time out — burning the
-- timeout budget and emitting a WARN per node per round, forever.
--
-- These rows are disabled, not deleted: the chain definitions stay visible in the operator
-- portal, and re-enabling one is a single UPDATE once a real endpoint is configured. Anyone
-- who has already replaced the URLs keeps their node enabled — the WHERE clauses match only
-- the literal seeded values.

-- 1. Infura placeholders — the seed literally ships `/v3/changeme`.
UPDATE rpc_node
SET    enabled = false
WHERE  url LIKE '%/v3/changeme';

-- 2. Fhenix and Inco. Both are experimental/withdrawn confidential-EVM testnets with no
--    reachable public endpoint; the token standards that target them are still exercised
--    against the Zama fhEVM path instead.
UPDATE rpc_node
SET    enabled = false
WHERE  url IN (
    'https://api.fhenix.zone:7747',
    'https://api.helium.fhenix.zone:7747',
    'https://mainnet.inco.org',
    'https://validator.rivest.inco.org'
);

-- 3. graph_node_url pointed 12 chains at http://graph-node:8000, a host that only exists if
--    indexer/evm/docker-compose.yml is started separately — and whose published ports 8000/8001
--    collide head-on with Kong's in the main stack, so the two cannot run side by side as
--    written. GraphNodeSyncService selects exactly the rows with a non-blank graph_node_url,
--    so this had it failing 12x every 30s until each chain hit its 10-consecutive-error ceiling.
--    Blanking the seed makes the subgraph indexer explicitly opt-in: set graph_node_url on the
--    chains you actually deploy a subgraph for.
UPDATE chain_config
SET    graph_node_url      = NULL,
       graph_subgraph_name = NULL
WHERE  graph_node_url = 'http://graph-node:8000/subgraphs/name';
