---
title: Anvil demo chain and address manifest
---

# Anvil demo chain and address manifest

The demo Compose stack runs Anvil on chain ID `11155111` and deploys live examples of every EVM
standard supported by Registerwerk: ERC-20, ERC-721, ERC-1155, ERC-3525, ERC-3643, ERC-4626 and
ERC-7540. It also seeds two repo markets, identity/permission infrastructure, customer holdings,
and 100 additional native ETH for each of five customer wallets. The operator is Anvil's first
pre-funded account and retains ample native ETH after deployment gas.

`demo-onchain-deploy` writes `/output/demo.env` and `/output/manifest.json` into the
`lending_demo_addresses` volume. The backend consumes the env artifact, replaces relational
placeholder addresses, configures its internal Anvil RPC node, and exposes a secret-free subset at
`GET /api/v1/demo/onchain`. The customer dashboard reads this endpoint, so UI labels, backend
deployments and on-chain contracts all derive from the same artifact.

Startup is idempotent. Addresses are reused only when both the factory and deployment-registry
bytecode still exist in persisted Anvil state; missing main-suite or ERC-3643 state is repaired
independently. Existing PostgreSQL demo volumes receive new standards through additive catalogue
reconciliation.

Anvil starts in its default auto-mine mode (a block per transaction, not a fixed interval) —
`demo-onchain-deploy`'s multi-hundred-transaction broadcast runs against that fast mode rather than
fighting a chain that keeps advancing empty blocks under it. Only once that broadcast finishes does
the deploy job switch Anvil to 2-second interval mining (`anvil_setIntervalMining 2`, retried for up
to 30 seconds since Anvil can be briefly slow to answer RPC calls right after a large deployment) —
interval mining is what lets a chaincache workload observe a steady sequence of blocks afterward for
the `SAFE`/`FINALIZED` promotion demo described below, rather than a chain that goes quiet the
moment deployment ends.

When `CHAINCACHE_ENABLED=true`, two [chaincache](chaincache-integration.md) workloads sit in front
of this chain (and one real public testnet): `chaincache-sepolia` (port `48090`) fronts this Anvil devnet with a `DEPTH_BASED`
finality model; `chaincache-base` (port `48091`) fronts real public Base Sepolia RPC providers with
a `TAG_BASED` model. Both are seeded as `CHAINCACHE`-kind nodes so the operator node list shows a
live comparison against the plain direct-RPC nodes also configured for demo chains.

```bash
docker compose up -d anvil softhsm
docker compose run --rm demo-onchain-deploy
curl http://localhost:48080/api/v1/demo/onchain
```

The factory is split into a 3 KB coordinator and one deployer module per standard. Every runtime
is below EIP-170 and the fixture deploys on a strict EVM; the demo does not disable size limits.

The `RegisterwerkDeploymentRegistry` is a UUPS proxy and records the current product address,
revision, timestamp and manifest hash per standard. Issued ERC-20/721/1155/3525/4626/7540 products
remain immutable. ERC-3643 uses its standard T-REX proxy suite. This intentionally limits the
upgrade surface to coordination and standards that already define proxy governance.
