-- RPC node governance and trust (Phase 4 K5: P4C-1 governance half, P4B-8, P4C-6 interim / parked T4-08).
--
-- chain_config.genesis_hash: the pinned genesis block hash (EVM block 0 hash, Solana getGenesisHash).
--   NULL until the first health check of a node whose eth_chainId matches the pinned chain_id has
--   captured it (compare-and-set, never overwritten by a node afterwards). A node that later answers a
--   different genesis is marked unhealthy with health_reason CHAIN_MISMATCH and is never routed to.
-- chain_config.rpc_allowed_hosts: optional comma-separated host allow-list for node URLs of this chain
--   (NULL / blank = any host). Enforced when a node is added or its URL changed.
-- rpc_node.health_reason: why the node is currently not healthy (CHAIN_MISMATCH, IMPLAUSIBLE_HEIGHT,
--   PROBE_FAILED, SYNCING, LAGGING, STALLED, RECOVERING); NULL while healthy. CHAIN_MISMATCH and
--   IMPLAUSIBLE_HEIGHT are quarantine reasons: such a node is not even a last-resort routing choice.
-- rpc_node.consecutive_successes: hysteresis counter; an unhealthy node needs 2 consecutive good probes
--   to be healthy again, but becomes unhealthy on the first failed probe.
ALTER TABLE chain_config
    ADD COLUMN genesis_hash       VARCHAR(80),
    ADD COLUMN rpc_allowed_hosts  TEXT;

ALTER TABLE rpc_node
    ADD COLUMN health_reason          VARCHAR(40),
    ADD COLUMN consecutive_successes  INTEGER NOT NULL DEFAULT 0;
