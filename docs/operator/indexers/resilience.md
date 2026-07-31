---
title: Resilience and Recovery
---

# Indexer Resilience

This page describes how the registry detects indexer gaps, recovers from outages, and compares
provisional event ranges. These procedures do not establish chain finality or legal correctness.

## Indexer state tracking

The backend maintains an `indexer_state` table that records the latest successfully indexed block for each chain:

```sql
SELECT chain_id, network_name, latest_indexed_block,
       chain_head_block,
       (chain_head_block - latest_indexed_block) AS lag_blocks,
       last_updated_at
FROM indexer_state
ORDER BY lag_blocks DESC;
```

The backend polls the graph-node's indexing status API every 30 seconds and updates this table.

## Gap detection

A gap occurs when the indexer falls behind the chain head. The backend classifies lag as:

| Lag (blocks) | Status | Action |
|-------------|--------|--------|
| 0–5 | OK | Normal operation |
| 6–20 | WARN | Warning logged, Prometheus alert fires |
| 21–100 | DEGRADED | Dashboard shows warning, operator email sent |
| 100+ | CRITICAL | `/actuator/health` returns `DOWN`, PagerDuty alert fires |

## Recovery procedures

### Graph Node recovery (EVM)

If the graph-node falls behind due to RPC downtime:

1. Check graph-node logs for errors:

   ```bash
   docker compose logs --tail=100 graph-node | grep -i "error\|panic"
   ```

2. Verify the RPC endpoint is reachable:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

3. If the RPC is down, update `.env` with a fallback RPC and restart:

   ```bash
   docker compose restart graph-node
   ```

4. Monitor recovery progress at `http://localhost:8030`.

### Subgraph re-indexing

If a subgraph has fatal errors and cannot recover automatically, do not remove the active
deployment. Render and deploy a fresh version under the configured mainnet graph name:

```bash
# Configure every *_MAINNET singleton and multi-instance list with its deployment block,
# then render, validate and deploy a uniquely labelled fresh version. Graph Node retains
# the prior version while ewpg/ethereum-mainnet indexes the replacement.
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Wait for the new version to reach the chain head, then compare its event range independently
before allowing downstream reliance. Keep the prior configuration and artifacts. If rollback is
required, redeploy that previously approved configuration under a new version label; this creates
a fresh version instead of destructively deleting either history.

### Solana indexer recovery

If the Solana indexer missed events during a gRPC outage:

1. Check the last successfully processed slot in the indexer state table
2. The indexer's polling fallback re-processes slots automatically when it reconnects
3. If the gap is too large (>10,000 slots), trigger a manual backfill:

   ```bash
   curl -X POST http://localhost:3001/backfill \
     -H "Content-Type: application/json" \
     -d '{"fromSlot": 285600000, "toSlot": 285614923}'
   ```

## Monitoring alerts

Configure Prometheus alerting rules in `monitoring/alerts.yml`:

```yaml
groups:
  - name: indexer
    rules:
      - alert: IndexerLagHigh
        expr: indexer_lag_blocks > 20
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Indexer lag > 20 blocks on {{ $labels.chain }}"

      - alert: IndexerLagCritical
        expr: indexer_lag_blocks > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Indexer lag CRITICAL on {{ $labels.chain }}"

      - alert: GraphNodeDown
        expr: up{job="graph-node"} == 0
        for: 1m
        labels:
          severity: critical
```

## Planned event-range comparison (not implemented)

Registerwerk does not currently expose a `verify-consistency` admin endpoint. A planned recovery
control will:

1. Queries the subgraph for all transfer events in the block range
2. Directly fetches the same events from the chain via `eth_getLogs`
3. Compares the two sets and reports any discrepancies

Until that control is implemented and tested, operators must perform an independently controlled
event-range comparison before resuming reliance. Even then, matching event sets would establish
agreement for the checked range only—not chain finality, legal register state, legal effect,
settlement, or deployed-code identity.

# Resilience and Recovery

## Failure modes and recovery

| Component | Failure | Recovery |
|---|---|---|
| graph-node | Stops indexing | On restart, continues from `last_indexed_block` |
| EVM RPC node | Connection lost | `GRAPH_ETHEREUM_REQUEST_RETRIES=10`; fallback RPCs configurable |
| Backend ↔ graph-node | Cannot reach GraphQL | `consecutive_errors` increments; resumes from cursor on reconnect |
| Yellowstone gRPC | Stream drops | Backend reconnects; polling job fills gaps |
| Solana RPC | Polling fails | `indexer_state.status = ERROR`; monitor alerts after 2h |

## Indexer monitor

`IndexerMonitorService` checks every 5 minutes whether `indexer_state.last_synced_at` is older than 2 hours. If so, it publishes an `INDEXER_STALE` audit event.

## Manual recovery

If an indexer gets significantly behind:

```bash
# Check current cursor
SELECT chain_config_id, indexer_type, last_synced_block, last_synced_at, status
FROM indexer_state;

# Reset cursor to force full re-sync (use with care)
UPDATE indexer_state SET last_synced_block = 0 WHERE chain_config_id = '<uuid>';
```

Then restart the relevant sync service or the backend.

## Deduplication

All events are stored with a UNIQUE constraint on `(chain_config_id, tx_hash, log_index)`. Re-syncing from an earlier block is safe — duplicates are silently ignored (`ON CONFLICT DO NOTHING`).
