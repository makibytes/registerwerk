# Starknet Indexer Sidecar

Local development stack providing a Starknet devnet with a JSON-RPC endpoint, so Cairo token
deployment and the backend's Starknet transfer indexer (`StarknetTransferSyncService`) can be
exercised end-to-end without depending on a public RPC provider.

**This is for development only.** In production, point the chain's RPC URL at a real Starknet
RPC provider (e.g. the seeded defaults `https://rpc.starknet.lava.build` /
`https://api.cartridge.gg/x/starknet/sepolia`, or your own node).

## Usage

```bash
# Start the local Starknet devnet
docker compose up -d

# Check health
docker compose logs -f

# Stop
docker compose down
```

The JSON-RPC endpoint is available at `http://localhost:5050` (also exposes a REST
`/is_alive` health check).

## Pointing Registerwerk at it

Starknet chain RPC URLs are **not** read from environment variables — they live in the
`chain_config` database table, editable via **Operator Portal → Network Nodes**. To use this
local devnet instead of the seeded hosted RPC:

1. Open the Operator Portal → Network Nodes.
2. Edit `STARKNET_SEPOLIA` (or add a new dev entry) and set the RPC URL to
   `http://starknet-devnet:5050` (from inside the Docker network) or
   `http://localhost:5050` (from the host).
3. Enable the chain (seeded rows start `enabled=false` until client integration is verified).

## What this runs

| Service | Port | Purpose |
|---------|------|---------|
| `starknet-devnet` | 5050 | JSON-RPC — backend connects here (`starknet_getEvents`, `starknet_addInvokeTransaction`, …) |

## Indexer coverage

The backend's `StarknetTransferSyncService` polls `starknet_getEvents` every 30s for the
standard Cairo `Transfer` event, filtered to contract addresses recorded in
`AssetDeployment.contractAddress` (populated at deploy time via UDC address precomputation —
no separate on-chain confirmation step is required before a deployment becomes trackable).
