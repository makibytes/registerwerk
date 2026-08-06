---
title: Indexer Configuration
---

# Indexer Configuration

## EVM indexer (graph-node)

graph-node is configured via `indexer/evm/config/graph-node.toml`. The TOML file defines which chains are indexed and which RPC providers to use.

### Adding a chain to graph-node

1. Add the RPC URL to `.env`:
   ```dotenv
   ARBITRUM_RPC=https://arb1.arbitrum.io/rpc
   ```

2. Add to `indexer/evm/docker-compose.yml` in the `ethereum` env var:
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```

3. Add to `indexer/evm/config/graph-node.toml`:
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   features = []
   ```

4. Restart graph-node so it loads the new network, and wait for it to become healthy:

   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```

5. Configure every static source using the `ARBITRUM` suffix and deploy with a unique version
   label:

   ```bash
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

   See [The Graph](../indexers/the-graph.md) for singleton variables, multi-instance lists and
   deployment-block requirements. These outputs are provisional event projections, not chain
   finality, legal register state, settlement evidence, or code-identity verification.

### Retry configuration

`GRAPH_ETHEREUM_REQUEST_RETRIES=10` is set by default. graph-node retries failed RPC calls before marking the indexer as failed.

## Solana indexer (Yellowstone)

Yellowstone is configured via `indexer/solana/config/yellowstone.toml`.

```toml
[grpc]
address = "0.0.0.0:10000"

[upstream]
endpoint = "${YELLOWSTONE_UPSTREAM_ENDPOINT}"
token = "${YELLOWSTONE_TOKEN}"
```

Set `YELLOWSTONE_UPSTREAM_ENDPOINT` to a Geyser-enabled Solana RPC endpoint.

## Backend sync configuration

In `application.yml`:

```yaml
ewpg:
  indexer:
    graph-node-poll-interval: 30s    # How often to query The Graph
    solana-poll-interval: 10m        # Fallback polling for Solana
    stale-threshold: 2h              # Alert if no sync for this long
```
