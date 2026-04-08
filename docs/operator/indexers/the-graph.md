---
id: the-graph
title: The Graph (EVM Indexer)
sidebar_label: The Graph
sidebar_position: 1
---

# The Graph — EVM Indexing

The eWpG Registry uses The Graph Protocol's `graph-node` to index on-chain events from all EVM chains.

## Architecture

```
EVM Chain (Ethereum, Polygon, Base, etc.)
        |
        | (eth_getLogs via RPC)
        v
  graph-node (Docker)
        |
        | (writes indexed data)
        v
  PostgreSQL (graph-node schema)
        |
        | (GraphQL)
        v
  Backend Spring Boot
```

## Subgraph structure

```
indexer/evm/subgraph/
  subgraph.template.yaml   # network names substituted at deploy time
  schema.graphql           # GraphQL entity schema
  src/handlers/
    factory.ts             # AssetTokenFactory event handlers
    token.ts               # Transfer, Mint, Burn handlers
    identity.ts            # IdentityRegistry event handlers
    compliance.ts          # Compliance module event handlers
  abis/                    # Contract ABIs
```

## Deploying the subgraph

```bash
cd indexer/evm/subgraph && npm install

# Sepolia
FACTORY_ADDRESS_SEPOLIA=0xYourFactory ../deploy-subgraph.sh sepolia

# Polygon Mainnet
FACTORY_ADDRESS_POLYGON=0xYourFactory ../deploy-subgraph.sh matic

# Base Mainnet
FACTORY_ADDRESS_BASE=0xYourFactory ../deploy-subgraph.sh base
```

The deploy script:
1. Substitutes addresses and network names into `subgraph.template.yaml`
2. Runs `graph codegen` then `graph build` (compiles WASM handlers)
3. Runs `graph create` and `graph deploy` against the local graph-node

## Monitoring subgraph health

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

A healthy subgraph shows `"synced": true` and `"health": "healthy"`.

## Adding a new chain to the subgraph

Add a new data source in `subgraph.template.yaml`:

```yaml
dataSources:
  - kind: ethereum
    name: AssetTokenFactory_arbitrum
    network: arbitrum-one
    source:
      address: "${FACTORY_ADDRESS_ARBITRUM}"
      abi: AssetTokenFactory
      startBlock: ${START_BLOCK_ARBITRUM}
    mapping:
      kind: ethereum/events
      apiVersion: 0.0.7
      language: wasm/assemblyscript
      entities: [Asset, Transfer]
      abis:
        - name: AssetTokenFactory
          file: ./abis/AssetTokenFactory.json
      eventHandlers:
        - event: AssetDeployed(indexed address,indexed address,uint8)
          handler: handleAssetDeployed
      file: ./src/handlers/factory.ts
```

Then deploy:

```bash
FACTORY_ADDRESS_ARBITRUM=0xYourFactory \
  START_BLOCK_ARBITRUM=200000000 \
  ../deploy-subgraph.sh arbitrum-one
```

## Re-indexing after contract upgrade

```bash
FACTORY_ADDRESS_SEPOLIA=0xNewFactory \
  START_BLOCK=12345678 \
  ../deploy-subgraph.sh sepolia
```

Setting `START_BLOCK` re-indexes from that block onward.

## Troubleshooting

### Subgraph shows "failed" health

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph health fatalError{message}}}"}' \
  | jq '.data.indexingStatuses[] | select(.health != "healthy")'
```

Common causes:
- RPC rate limiting — reduce `GRAPH_ETHEREUM_TARGET_TRIGGERS_PER_BLOCK_RANGE`
- ABI mismatch — ensure ABI files match deployed contracts
- OOM — increase graph-node memory limit in docker-compose

### Subgraph far behind chain head

Check graph-node logs:

```bash
docker compose logs -f graph-node | grep "indexing blocks"
```

If processing is slow, the RPC provider may be throttling. Switch to a paid tier or add a fallback provider in `graph-node.toml`.

# The Graph — EVM Indexer

The Graph's `graph-node` indexes all EVM chains. Each token's full transfer history is available via GraphQL.

## Starting graph-node

```bash
docker compose -f indexer/evm/docker-compose.yml up -d
```

This starts:
- `graph-node` — the indexer (port 8000 GraphQL, 8020 admin)
- `ipfs` — required for subgraph deployment
- `postgres` — graph-node's own database (separate from app DB)

## Deploying a subgraph

Deploy to a single chain:
```bash
FACTORY_ADDRESS_SEPOLIA=0xYourFactory \
  ./indexer/evm/deploy-subgraph.sh sepolia
```

Deploy to all configured chains:
```bash
FACTORY_ADDRESS_SEPOLIA=0x... \
FACTORY_ADDRESS_POLYGON_AMOY=0x... \
  ./indexer/evm/deploy-subgraph.sh all
```

## Auto-registration of new tokens

When `AssetTokenFactory` deploys a new token, it emits `TokenDeployed(address token, string standard, bytes32 assetId)`. The subgraph's `handleTokenDeployed` function automatically creates a new dynamic data source for the token — no subgraph redeployment needed.

```typescript
// factory.ts
export function handleTokenDeployed(event: TokenDeployed): void {
  if (event.params.standard == 'ERC20') {
    EwpgERC20.create(event.params.token);
  } else if (event.params.standard == 'ERC721') {
    EwpgERC721.create(event.params.token);
  }
  // …
}
```

## Syncing to backend

`GraphNodeSyncService` in the backend polls every 30 seconds:
1. Queries GraphQL from the cursor stored in `indexer_state.last_synced_block`
2. Upserts new transfers into `token_transfer` (UNIQUE constraint deduplicates)
3. Updates `indexer_state.last_synced_block` and `last_synced_at`

## Querying directly

```bash
curl -X POST http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia \
  -H "Content-Type: application/json" \
  -d '{"query": "{ transfers(orderBy: blockNumber, first: 10) { id from to amount eventType transactionHash blockTimestamp } }"}'
```

## Adding a new EVM chain

See [Chain Configuration](../configuration/chains) for step-by-step instructions.
