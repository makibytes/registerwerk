---
id: adding-chains
title: Adding New Chains
sidebar_position: 4
---

# Adding New Chains

New blockchains can be added at runtime — no code changes or redeployments required.

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

### 4. Deploy and start subgraph

```bash
FACTORY_ADDRESS_OPTIMISM=0xYourFactory \
  ./indexer/evm/deploy-subgraph.sh optimism-mainnet
```

### 5. Trigger client refresh

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
