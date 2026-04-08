---
id: chains
title: Chain Configuration
sidebar_position: 2
---

# Chain Configuration

The eWpG Registry stores chain configuration in the `chain_config` table. This means you can add new blockchains at runtime without redeploying the backend.

## Pre-configured chains

The following chains are seeded by the Flyway migrations:

| Identifier | Chain | Type | Chain ID |
|---|---|---|---|
| ETHEREUM_MAINNET | Ethereum | EVM Mainnet | 1 |
| ETHEREUM_SEPOLIA | Ethereum Sepolia | EVM Testnet | 11155111 |
| POLYGON_MAINNET | Polygon | EVM Mainnet | 137 |
| POLYGON_AMOY | Polygon Amoy | EVM Testnet | 80002 |
| BASE_MAINNET | Base | EVM Mainnet | 8453 |
| BASE_SEPOLIA | Base Sepolia | EVM Testnet | 84532 |
| SOLANA_MAINNET | Solana | Solana Mainnet | — |
| SOLANA_DEVNET | Solana Devnet | Solana Testnet | — |
| FHENIX_MAINNET | Fhenix | EVM Mainnet (FHE) | 21888 |
| FHENIX_HELIUM | Fhenix Helium | EVM Testnet (FHE) | 8008135 |
| INCO_MAINNET | Inco | EVM Mainnet (FHE) | 9090 |
| INCO_RIVEST | Inco Rivest | EVM Testnet (FHE) | 21097 |

## Adding a new EVM chain

### Step 1 — Register via Admin API

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "ARBITRUM_MAINNET",
    "displayName": "Arbitrum One",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 42161,
    "rpcUrl": "https://arb1.arbitrum.io/rpc",
    "wsUrl": "wss://arb1.arbitrum.io/ws",
    "blockExplorerUrl": "https://arbiscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/arbitrum-mainnet"
  }'
```

The backend's `BlockchainClientRegistry` picks up the new chain on next refresh (every 60 seconds) or immediately via:

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Step 2 — Add to graph-node

In `indexer/evm/docker-compose.yml`, add to the `ethereum` env var:
```
,arbitrum-one:${ARBITRUM_RPC}
```

In `indexer/evm/config/graph-node.toml`:
```toml
[chains.arbitrum-one]
shard = "primary"
protocol = "ethereum"
[[chains.arbitrum-one.provider]]
url = "${ARBITRUM_RPC}"
features = []
```

### Step 3 — Deploy and index subgraph

```bash
FACTORY_ADDRESS_ARBITRUM=0xYourFactory ./indexer/evm/deploy-subgraph.sh arbitrum-one
```

### Step 4 — Restart indexer

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

## FHE chains (Fhenix / Inco)

Fhenix and Inco chains use the Zama fhEVM and support confidential ERC-3643 tokens. They are pre-seeded in V15. Deploy the `ConfidentialERC3643` contract using:

```bash
forge script script/Deploy.s.sol --rpc-url $FHENIX_HELIUM_RPC --broadcast
```

The backend's `ConfidentialErc3643Service` handles encrypted transfer operations on these chains.
