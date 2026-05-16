# Canton Indexer Sidecar

Local development stack for Registerwerk's Canton integration.
Runs a single-participant, single-domain Canton node that the backend connects to.

**This is for development only.** In production, operators provision their own participant
(on-premises or cloud-hosted) and supply its Ledger API URL via environment variables.

## Usage

```bash
# Start the local Canton participant
docker compose up -d

# Check health
docker compose logs -f

# Stop
docker compose down
```

The Ledger API is available at `localhost:5001` (gRPC).
Set `CANTON_DEVNET_LEDGER_URL=localhost:5001` in your backend `.env`.

## What this runs

| Service | Port | Purpose |
|---------|------|---------|
| `canton` | 5001 | Ledger API (gRPC) — backend connects here |
| `canton` | 5003 | Admin API (gRPC) — party/package management |

## Topology

- One participant: `registerwerk`
- One domain: `local`
- Participant is connected to the domain at startup via `bootstrap.canton`
- In-memory storage — all state is lost on container restart

## Daml Token Standard DAR

The bootstrap script optionally uploads the Token Standard DAR.
Build it first:

```bash
cd ../../daml && ./build.sh
```

## Public Canton Network (devnet)

To connect to the public Canton DevNet instead of a local node:

1. Register at https://canton.network
2. Set `CANTON_DEVNET_LEDGER_URL` to your participant's Ledger API endpoint
3. Set `CANTON_DEVNET_SYNCHRONIZER=dev-synchronizer`
4. Skip running this docker compose — the public network has the Token Standard pre-deployed
