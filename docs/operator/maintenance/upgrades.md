---
title: Upgrading the Registry
---

# Upgrading the Registry

This page covers upgrading the backend, frontends, and smart contracts. Follow the procedures in order — never upgrade contracts before upgrading the backend.

## Backend upgrade

### 1. Pull the latest changes

```bash
git fetch origin
git pull origin main
git submodule update --recursive
```

### 2. Review the release

Review the commits and deployment configuration changes between the currently deployed tag and the target tag before proceeding.

### 3. Build the new backend image

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

Or pull from the container registry:

```bash
docker pull ghcr.io/ewpg/registerwerk-backend:latest
```

### 4. Apply the upgrade

```bash
# Stop the backend gracefully (drains in-flight requests)
docker compose stop backend

# Start the new version — Flyway runs migrations automatically on startup
docker compose up -d backend

# Verify health
docker compose logs -f backend | grep -E "Started|ERROR"
curl http://localhost:48080/actuator/health
```

!!! warning
    Database migrations run automatically on startup. If a migration fails, the backend will not start. Check logs for the specific migration error. Never manually modify the Flyway history table.


### 5. Verify

After startup:
- Check the API at `http://localhost:48080/swagger-ui.html`
- Create a test API call against a critical endpoint
- Monitor the audit log for any unexpected errors in the first 15 minutes

## Frontend upgrade

```bash
# Operator frontend
cd frontend-operator
npm install
ng build --configuration production
docker compose up -d --build frontend-operator

# Customer frontend
cd ../frontend-customer
npm install
ng build --configuration production
docker compose up -d --build frontend-customer
```

Frontends are stateless — upgrades are zero-downtime.

## Smart contract upgrades

!!! warning
    Smart contract upgrades are the most sensitive operations. All contracts go through a testnet deployment and audit before any mainnet upgrade. Never upgrade mainnet contracts without completing testnet validation first.


### Upgradeable vs. non-upgradeable contracts

| Contract | Upgradeable | Upgrade path |
|----------|------------|-------------|
| `AssetTokenFactory` | No (CREATE2 factory) | Deploy new factory, update backend config |
| `RegisterwerkDeploymentRegistry` | Yes (UUPS proxy) | Multisig-authorized implementation upgrade |
| `EwpgTREXFactory` | No | Deploy new factory |
| `IdentityRegistryStorage` | Yes (UUPS proxy) | Upgrade proxy implementation |
| `ModularCompliance` | Yes (UUPS proxy) | Upgrade proxy implementation |
| Token contracts (per issuance) | No | Cannot be upgraded after deployment |

Registerwerk applies proxies selectively. The address catalogue is upgradeable because it is a
coordination service; ordinary issued products remain immutable. ERC-3643 keeps the audited T-REX
proxy model. Before any UUPS upgrade, archive `forge inspect <Contract> storage-layout` output,
compare it with the candidate implementation, run the upgrade/storage-preservation tests, and
record the implementation bytecode hash in the change ticket.

### Upgrading a UUPS proxy contract

```bash
cd contracts
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow
```

The upgrade script:
1. Deploys the new implementation contract
2. Calls `upgradeToAndCall` on the UUPS proxy
3. Verifies the new implementation is active

### Upgrading compliance modules

Compliance modules can be added, removed, or replaced without upgrading the token contract itself. This is the preferred upgrade path for compliance logic changes.

```bash
# Add a new compliance module to a token
curl -X POST http://localhost:48080/api/v1/admin/tokens/{tokenAddress}/compliance/modules \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"moduleAddress": "0xNewModuleAddress", "chain": "mainnet"}'
```

## Rollback procedure

If an upgrade causes issues, roll back by reverting to the previous Docker image tag:

```bash
# Backend rollback
docker compose stop backend
docker tag ghcr.io/ewpg/registerwerk-backend:previous \
  registerwerk-backend:latest
docker compose up -d backend
```

Flyway migrations in this repository have no automatic down scripts. If a release changes the schema, restore the pre-upgrade database backup together with the previous application image, or deploy a reviewed forward-fix migration.

## Kong upgrade

```bash
docker compose stop kong
docker compose pull kong
docker compose up -d kong
```

After upgrading Kong, re-apply the declarative configuration:

```bash
deck sync --config gateway/kong.yml
```
sidebar_position: 3
---

# Upgrades

## Backend upgrade

1. Pull new image or build locally:
   ```bash
   docker build -t registerwerk-backend:v2.0.0 backend/
   ```

2. Update `docker-compose.yml` image tag

3. Start with rolling restart (Flyway auto-migrates):
   ```bash
   docker compose up -d --no-deps backend
   ```

4. Verify health: `curl http://localhost:48080/actuator/health`

## Smart contract upgrades

Compliance modules support in-place upgrade via `UpgradeCompliance.s.sol`:

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

Token and identity contracts are **not upgradeable by design** (immutability is a legal requirement for securities). Upgrades require deploying a new suite and migrating investors.

## Subgraph upgrades

If the subgraph schema changes, preserve the previous configuration and deploy a fresh version.
Before deployment, verify every singleton address has its own actual `*_START_BLOCK_<SUFFIX>` and
every BondDesk, AMM, and RepoVault entry uses `address@deploymentBlock`. One factory block is not a
valid substitute for the deployment blocks of the other sources.

Render and compile all configured targets without publishing first:

```bash
SUBGRAPH_VALIDATE_ONLY=true ./indexer/evm/deploy-subgraph.sh all
```

Then deploy with a version label that has never been used for the affected graph names:

```bash
SUBGRAPH_VERSION_LABEL=schema-20260729-01 ./indexer/evm/deploy-subgraph.sh all
```

graph-node reindexes each rendered source from that source's configured block. Keep the previous
versions and their configuration available for non-destructive rollback until every replacement
has reached the chain head and its event ranges have been reconciled independently. Do not remove
the prior subgraph before validation; rollback means redeploying the previously approved manifest
and source configuration under another fresh version label.

## Kong upgrades

1. Update the `kong` image tag in `docker-compose.yml` (and `gateway/docker-compose.kong.yml`
   if using the standalone gateway-only stack).
2. Restart Kong: `docker compose restart kong` — it re-reads `gateway/kong.yml` on start
   (DB-less mode, no migrations to run).

## Dependency updates

- **Java / Spring Boot**: Update `pom.xml`, run `mvn verify`
- **Angular**: `ng update @angular/core @angular/cli`
- **Contracts**: `forge update` (updates git submodules in `contracts/lib/`)
