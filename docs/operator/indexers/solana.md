---
title: Solana (Yellowstone gRPC)
---

# Solana Indexer — Yellowstone gRPC

The Solana indexer uses **Yellowstone**, a Geyser plugin that streams real-time account updates and transactions via gRPC. A polling fallback covers periods when the gRPC stream is unavailable.

## Architecture

```
Solana Validator (with Yellowstone Geyser plugin)
        |
        | (gRPC stream)
        v
  indexer/solana (Node.js service)
        |
        | (REST / WebSocket)
        v
  Backend Spring Boot
```

## Prerequisites

- A Solana RPC endpoint with **Yellowstone gRPC** support
  - Hosted options: [Helius](https://helius.dev), [Triton](https://triton.one)
  - Self-hosted: requires a Solana validator with the Yellowstone plugin installed

## Configuration

`indexer/solana/yellowstone.yaml`:

```yaml
endpoint: "${SOLANA_YELLOWSTONE_ENDPOINT}"
x_token: "${SOLANA_YELLOWSTONE_TOKEN}"

subscriptions:
  - type: transaction
    accountInclude:
      - "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"  # SPL Token program
    commitment: confirmed

polling:
  enabled: true
  fallback_rpc: "${SOLANA_MAINNET_RPC_URL}"
  poll_interval_seconds: 2
  max_blocks_per_poll: 10
```

Set in `.env`:

```bash
SOLANA_YELLOWSTONE_ENDPOINT=https://your-yellowstone-endpoint.helius.xyz:2083
SOLANA_YELLOWSTONE_TOKEN=your_api_token
SOLANA_MAINNET_RPC_URL=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC_URL=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

## Starting the Solana indexer

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Or for local development:

```bash
cd indexer/solana && npm install
npm start
```

## SPL token tracking

The Solana indexer tracks SPL token program events:
- `Transfer` — token transfers between accounts
- `MintTo` — token minting
- `Burn` — token burning

It filters events to only those involving token mint addresses registered in the backend. Unrelated SPL token activity is ignored.

## Polling fallback

If the Yellowstone gRPC stream disconnects or becomes unavailable, the indexer automatically switches to polling mode:

1. Polls `getSignaturesForAddress` for the SPL Token program at the configured interval
2. Fetches full transaction details for each new signature
3. Parses token events from transaction logs
4. Switches back to gRPC as soon as the stream reconnects

The polling fallback is less efficient but ensures no events are missed during provider outages.

## Monitoring

The Solana indexer exposes a health endpoint:

```bash
curl http://localhost:3001/health
```

Response:

```json
{
  "status": "healthy",
  "mode": "grpc",
  "latestSlot": 285614923,
  "lastEventAt": "2025-04-06T12:00:00Z"
}
```

If `mode` shows `polling`, the gRPC stream is down and the fallback is active. Investigate the Yellowstone endpoint.

## Adding Solana Devnet

The devnet indexer runs as a separate instance:

```bash
SOLANA_NETWORK=devnet \
  SOLANA_YELLOWSTONE_ENDPOINT=https://devnet-yellowstone.example.com:2083 \
  SOLANA_YELLOWSTONE_TOKEN=your_devnet_token \
  SOLANA_DEVNET_RPC_URL=https://api.devnet.solana.com \
  npm start
```

Or add a second service in the Docker Compose file with `SOLANA_NETWORK=devnet`.

# Solana — Yellowstone gRPC Indexer

Solana transfers are monitored via [Yellowstone Dragon's Mouth](https://github.com/rpcpool/yellowstone-grpc), a gRPC proxy for Solana's Geyser Plugin Interface.

## Starting Yellowstone

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Set `YELLOWSTONE_UPSTREAM_ENDPOINT` to a Geyser-enabled endpoint:
- [Helius](https://helius.dev)
- [Triton One](https://triton.one)
- A self-hosted Solana validator with the Yellowstone plugin

## How it works

`SolanaTransferSyncService` in the backend:
1. Opens a gRPC subscription to Yellowstone on startup (`@PostConstruct`)
2. Filters transactions involving any known SPL mint address
3. Parses transfers and upserts into `token_transfer`

If the gRPC stream drops, the service reconnects automatically.

## Polling fallback

A separate `@Scheduled` job runs every 10 minutes:
- Calls `getSignaturesForAddress` for every known SPL mint
- Fills any gaps caused by stream downtime
- Deduplication via UNIQUE constraint prevents double-counting

## Registering a new SPL token

When a Solana asset deployment is created via the API, the backend automatically starts monitoring its mint address. No manual configuration needed.
