# Stellar Indexer Sidecar

Local development stack running a Stellar quickstart node (test network + Horizon REST API),
so Stellar asset issuance and the backend's Horizon transfer indexer
(`StellarTransferSyncService`) can be exercised end-to-end without depending on the public
testnet Horizon.

**This is for development only.** In production, point the chain's Horizon URL at a real
Horizon instance (e.g. the seeded defaults `https://horizon.stellar.org` /
`https://horizon-testnet.stellar.org`, or your own).

## Usage

```bash
# Start the local Stellar test network + Horizon
docker compose up -d

# Check health (Horizon can take ~30-60s to start producing ledgers)
docker compose logs -f

# Stop
docker compose down
```

Horizon is available at `http://localhost:8000`.

## Pointing Registerwerk at it

Stellar Horizon URLs are **not** read from environment variables — they live in the
`chain_config` database table, editable via **Operator Portal → Network Nodes**. To use this
local node instead of the seeded hosted Horizon:

1. Open the Operator Portal → Network Nodes.
2. Edit `STELLAR_TESTNET` (or add a new dev entry) and set the RPC URL to
   `http://stellar:8000` (from inside the Docker network) or `http://localhost:8000` (from
   the host).
3. Enable the chain (seeded rows start `enabled=false` until client integration is verified).

## Indexer coverage

The backend's `StellarTransferSyncService` cursor-polls Horizon's `/payments` endpoint every
30s for operations touching a tracked asset's issuing account (the account address is recorded
in `AssetDeployment.contractAddress` at issuance time — no separate confirmation step is
required). Only payments touching the issuing account are indexed today (issuance, redemption,
and any transfer routed through it); pure holder-to-holder secondary transfers that never touch
the issuer are a known follow-up, not yet covered.
