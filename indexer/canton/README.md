# Canton sandbox

Local development node for Registerwerk's Canton integration: one participant and one synchronizer in a
single JVM (Canton's built-in `sandbox`), in-memory storage, Canton 3.6.2.

**This is for development only.** In production, operators provision their own participant
(on-premises or cloud-hosted) and supply its Ledger API URL via environment variables.

## Usage

```bash
cd daml && dpm install && dpm build        # builds .daml/dist/registerwerk-canton-0.1.0.dar (see daml/README.md)
cd ../indexer/canton
docker compose up -d --build               # builds the image from the pinned Canton release, ~20 s to healthy
docker compose logs -f
docker compose down
```

The Ledger API is on `localhost:5001` (gRPC), the Admin API on `localhost:5002` and the JSON Ledger API on
`localhost:5013` (`/livez`, `/readyz`, `/v2/packages`). Set `CANTON_DEVNET_LEDGER_URL=localhost:5001` in
your backend `.env`.

## What this runs

The image is built from the Canton release archive on GitHub, checked against a pinned SHA-256 (the
`digitalasset/canton-open-source` repository on Docker Hub only holds Canton 2.x). To change the Canton
version, change `CANTON_VERSION` **and** `CANTON_SHA256` in `Dockerfile`.

- State is lost when the container stops.
- If `daml/.daml/dist/registerwerk-canton-0.1.0.dar` exists, it is uploaded at start-up, so the backend can
  address the bond templates by package name (`#registerwerk-canton`). Without it the entrypoint only prints
  a hint.
- `config/sandbox.conf` binds the APIs to `0.0.0.0`; the ports are set in `docker-entrypoint.sh`.

## Public Canton Network (devnet)

To connect to the public Canton DevNet instead of a local node:

1. Register at https://canton.network
2. Set `CANTON_DEVNET_LEDGER_URL` to your participant's Ledger API endpoint
3. Set `CANTON_DEVNET_SYNCHRONIZER=dev-synchronizer`
4. Skip running this docker compose — the public network has the Token Standard pre-deployed
