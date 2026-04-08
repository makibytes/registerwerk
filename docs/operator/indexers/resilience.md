---
id: resilience
title: Resilience and Recovery
sidebar_label: Resilience
sidebar_position: 3
---

# Indexer Resilience

This page describes how the registry detects indexer gaps, recovers from outages, and ensures on-chain data integrity.

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

If a subgraph has fatal errors and cannot recover automatically:

```bash
# Remove the failed subgraph
curl -X POST http://localhost:8020 \
  -d '{"jsonrpc":"2.0","method":"subgraph_remove","params":{"name":"ewpg/mainnet"},"id":1}'

# Re-deploy from the correct start block
FACTORY_ADDRESS_MAINNET=0xYourFactory \
  START_BLOCK=18000000 \
  ../deploy-subgraph.sh mainnet
```

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

## Data consistency verification

To verify that registry data matches what is on-chain, use the built-in consistency checker:

```bash
curl -X POST http://localhost:8080/api/v1/admin/verify-consistency \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"chainId": 1, "fromBlock": 18000000, "toBlock": 18100000}'
```

The consistency checker:
1. Queries the subgraph for all transfer events in the block range
2. Directly fetches the same events from the chain via `eth_getLogs`
3. Compares the two sets and reports any discrepancies

Run this after any indexer recovery to confirm data integrity before resuming normal operations.

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
