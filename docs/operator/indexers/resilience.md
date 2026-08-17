---
title: Resilience and Recovery
---

# Indexer Resilience

This page describes how the registry detects indexer gaps, detects and recovers from chain
reorgs, and recovers from outages. These procedures do not establish legal correctness — see
`docs/operator/indexers/the-graph.md` for what a subgraph's `synced: true` does and does not mean.

!!! note "Corrected against the actual implementation"
    An earlier version of this page described a `chain_head_block`/`latest_indexed_block` schema,
    a `DEGRADED`/`CRITICAL` health-tier ladder, and a `POST /backfill` endpoint — none of which
    exist in this codebase. This page now describes what is actually implemented.

## Indexer state tracking

The backend maintains an `indexer_state` table, one row per `(chain_config_id, indexer_type)`:

```sql
SELECT chain_config_id, indexer_type, status,
       last_synced_block, last_final_block, last_synced_at,
       consecutive_errors, last_error
FROM indexer_state
ORDER BY last_synced_at ASC NULLS FIRST;
```

- `last_synced_block` — the head/provisional cursor: the highest block the indexer has read
  transfers from at all, whether or not those rows have since been finalized.
- `last_final_block` — the confirmed cursor: the highest block whose `token_transfer` rows have
  all cleared the configured confirmation depth and been verified against a freshly re-fetched
  canonical hash/status (EVM, Starknet). Always `<= last_synced_block`. Null for chains with no
  two-tier finality model (Solana/Stellar/Canton — see below).
- `status` — `ACTIVE`, `PAUSED`, or `ERROR`. An indexer in `ERROR` with
  `consecutive_errors >= 10` (5 for Canton) stops running until manually reset (see
  [Manual recovery](#manual-recovery)) — there is no automatic self-healing past that point.

## Chain reorg detection and recovery

Every EVM chain (via graph-node) and Starknet chain re-verifies its still-provisional window on
every sync tick:

- **EVM** — `token_transfer.block_hash` is recorded for rows within the configured confirmation
  depth (`registerwerk.blockchain.tx.confirmations-by-chain`); `ReorgGuard` re-fetches each such
  block's canonical hash from graph-node's `_meta(block: {number})` and compares. A mismatch marks
  every row at and after the fork point `ORPHANED` (never deleted — this is a regulated register
  with an audit-trail requirement) and rewinds `last_synced_block`/`last_final_block` to
  `fork_block - 1`, so the next tick re-indexes the affected range.
- **Starknet** — no block-hash primitive is used; instead each provisional row's finality is
  re-checked via `starknet_getBlockWithTxHashes`' `status` field. Rows only reach FINAL once
  `ACCEPTED_ON_L1`; a `REJECTED` block triggers the same orphan-and-rewind path as EVM.
- **Solana** — finality is established at write time: transfers are only indexed at
  `commitment: "finalized"`, and the signature's `err` field is checked so a failed transaction is
  never indexed as a successful transfer. There is no separate provisional window to re-verify.
- **Stellar / Canton** — ledger close (Stellar/Horizon) and synchronizer commit (Canton) are final
  once observed; same reasoning as Solana.

`token_transfer.finality_status ∈ {PROVISIONAL, FINAL, ORPHANED}` is queryable directly:

```sql
SELECT chain_config_id, finality_status, count(*)
FROM token_transfer
WHERE finality_status <> 'FINAL'
GROUP BY chain_config_id, finality_status;
```

A nonzero `ORPHANED` count is expected transiently right after a real reorg; a count that isn't
shrinking on subsequent ticks means the affected range is not successfully re-indexing — check
`indexer_state.last_error` for that chain.

**Known limitation:** there is no trusted-RPC/quorum policy — a single configured RPC endpoint's
`_meta`/status response is trusted as-is for reorg detection. A misbehaving or lagging RPC node can
itself produce a false reorg signal; corroborate against `docs/operator/blockchain/adding-chains.md`'s
RPC configuration before treating a reorg alert as a confirmed chain event.

## Indexer lag monitoring

`IndexerMonitorService` runs every 5 minutes and publishes two Prometheus gauges:

- `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id, indexer_type}` — Unix epoch
  seconds of the last successful sync. Alert as `time() - <metric> > threshold`.
- `registerwerk_indexer_lag_blocks{chain_config_id, indexer_type}` — blocks between
  `last_synced_block` and the highest `latest_block_number` reported by any enabled+healthy
  `rpc_node` on that chain (reusing `RpcNodeHealthService`'s already-cached chain-head data, not a
  fresh RPC call). Absent — not zero — for a chain with no healthy node or no synced block yet, so
  a missing series means "no data", not "no lag".

It also publishes an `INDEXER_STALE` audit event whenever an indexer is `ERROR` or hasn't synced in
over 2 hours. See `monitoring/alerts/registerwerk.yml`'s `registerwerk.critical` group
(`IndexerStaleCritical`/`IndexerStaleWarning`) and `registerwerk.observability` group
(`IndexerLagBlocksHigh`, alerting at >1000 blocks sustained for 10+ minutes) for the actual
Prometheus rules — both are real, evaluated rules in this repository, not illustrative examples.

## Manual recovery

An indexer that has hit `consecutive_errors >= 10` (5 for Canton) stops syncing until reset.

```bash
# List every indexer's current state
curl -H "Authorization: Bearer $OPERATOR_JWT" http://localhost:8080/api/v1/indexers

# Clear the error state — the next scheduled tick resumes from the existing cursor, no restart required
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:8080/api/v1/indexers/<indexer-state-id>/reset"

# Force a full re-sync from genesis instead (only if the existing cursor itself is untrustworthy —
# e.g. after a manual chain-state correction; this re-processes the chain's entire history)
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:8080/api/v1/indexers/<indexer-state-id>/reset?fullResync=true"
```

Both actions require `REGISTRY_ADMIN` and are audited (`INDEXER_RESET`). Equivalent direct SQL
(e.g. for a scripted/emergency path without the API) remains:

```sql
SELECT id, chain_config_id, indexer_type, last_synced_block, last_synced_at, status, consecutive_errors, last_error
FROM indexer_state;

UPDATE indexer_state SET status = 'ACTIVE', consecutive_errors = 0, last_error = NULL WHERE id = '<uuid>';
```

## Deduplication

`token_transfer` has `UNIQUE NULLS NOT DISTINCT` constraints on `(chain_config_id, tx_hash,
log_index)` (EVM/Starknet) and `(chain_config_id, tx_hash, slot)` (Solana). Re-syncing from an
earlier block is safe: each sync service checks `existsByChainConfigIdAndTxHashAndLogIndex`
before inserting, so a re-processed range is silently skipped rather than duplicated or erroring.

## Failure modes and recovery

| Component | Failure | Recovery |
|---|---|---|
| graph-node | Stops indexing / reports `hasIndexingErrors` | Backend degrades that tick to "write everything PROVISIONAL, skip reorg re-verification" rather than failing the sync; investigate graph-node directly |
| EVM/Starknet/Stellar RPC | Connection lost | `consecutive_errors` increments; resumes from cursor on reconnect; `ERROR` status after 10 consecutive failures |
| Solana Yellowstone gRPC | Stream drops | Polling fallback (`SolanaTransferSyncService`) fills gaps on its own 10-minute cron regardless of stream health |
| Canton ledger stream | Stream drops | `ERROR` status after 5 consecutive failures (lower threshold than other chains — see `CantonTransferSyncService`) |

## Subgraph re-indexing (EVM only)

If a subgraph has fatal errors and cannot recover automatically, do not remove the active
deployment. Render and deploy a fresh version under the configured mainnet graph name:

```bash
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Wait for the new version to reach the chain head, then compare its event range independently
before allowing downstream reliance. Keep the prior configuration and artifacts; if rollback is
required, redeploy the previously approved configuration under a new version label rather than
destructively deleting either version's history.
