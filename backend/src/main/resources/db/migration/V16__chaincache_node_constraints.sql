-- Closes two real gaps V12 left open: no uniqueness constraint on rpc_node(chain_config_id, url)
-- let the same URL be added twice (a compounding bug with RpcNodeService#redetectAll's old
-- single-probe-failure demotion — see V16's sibling app-code fix — produced exactly this in a live
-- demo environment), and no CHECK constraints meant an invalid enum string or a CHAINCACHE-kind
-- row missing its management_url/remote_chain_key could only ever be caught application-side.

-- Collapse any pre-existing duplicate (chain_config_id, lower(url)) groups down to one row before
-- the unique index below can be created. Keeps the row with the most recent last_checked_at (the
-- one with real health history), tie-broken by the lowest id for determinism when neither row has
-- ever been checked. Not flagged by check-destructive-migrations.sh (DELETE without DROP/TRUNCATE
-- is deliberately out of its scope — see that script's own comment), and not a statement this repo
-- would normally route through RetentionSweepJob instead: these are duplicate rows from a bug, not
-- a retention decision.
DELETE FROM rpc_node a
USING rpc_node b
WHERE a.chain_config_id = b.chain_config_id
  AND lower(a.url) = lower(b.url)
  AND a.id <> b.id
  AND (
    (a.last_checked_at IS NULL AND b.last_checked_at IS NOT NULL)
    OR (a.last_checked_at IS NOT NULL AND b.last_checked_at IS NOT NULL AND a.last_checked_at < b.last_checked_at)
    OR (a.last_checked_at IS NOT NULL AND b.last_checked_at IS NOT NULL AND a.last_checked_at = b.last_checked_at AND a.id > b.id)
    OR (a.last_checked_at IS NULL AND b.last_checked_at IS NULL AND a.id > b.id)
  );

CREATE UNIQUE INDEX ux_rpc_node_chain_url ON rpc_node (chain_config_id, lower(url));

ALTER TABLE rpc_node ADD CONSTRAINT ck_rpc_node_kind
    CHECK (kind IN ('DIRECT_RPC', 'CHAINCACHE'));

ALTER TABLE rpc_node ADD CONSTRAINT ck_rpc_node_chaincache_fields
    CHECK (kind <> 'CHAINCACHE' OR (management_url IS NOT NULL AND remote_chain_key IS NOT NULL));

ALTER TABLE chain_config ADD CONSTRAINT ck_chain_config_finality_source
    CHECK (finality_source IN ('RPC_SELF_PROBE', 'CHAINCACHE'));

-- Consecutive-failure counter for RpcNodeService#redetectAll's demotion hysteresis: a CHAINCACHE
-- node now only falls back to DIRECT_RPC after several consecutive failed probes, not one transient
-- blip (the mechanism that produced the duplicate rows this migration just cleaned up).
ALTER TABLE rpc_node ADD COLUMN chaincache_probe_failures INT NOT NULL DEFAULT 0;
