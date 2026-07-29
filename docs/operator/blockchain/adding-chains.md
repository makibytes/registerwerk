---
id: adding-chains
title: Adding New Chains
sidebar_position: 4
---

# Adding New Chains

Backend chain clients can be registered at runtime. EVM indexing also requires graph-node network
configuration and a supported deploy target with explicit contract sources.

## Supported chain types

| Type | Examples |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, any EVM-compatible |
| `SOLANA` | Solana Mainnet, Devnet |

## Adding an EVM chain (full walkthrough)

### 1. Register via Admin API

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Deploy contracts

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Add to graph-node config

See [Indexer Configuration](../configuration/indexers) for the TOML and docker-compose changes.

### 4. Restart graph-node with the new network

The deployment admin API cannot accept a manifest for the new network until graph-node has
reloaded its chain configuration:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Verify graph-node is healthy before continuing.

### 5. Configure and deploy the subgraph

Configure every `*_OPTIMISM` source described in [The Graph](../indexers/the-graph), then:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

The subgraph is a provisional event projection. It does not establish chain finality, legal
effect, authoritative register state, or deployed-code identity.

### 6. Trigger client refresh

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The `BlockchainClientRegistry` creates a new Web3j client for the chain immediately.

## Fallback RPCs

You can configure multiple RPC URLs for failover. The chain config stores `fallback_rpc_urls` as a comma-separated list. If the primary RPC fails, the registry tries fallbacks in order.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```
