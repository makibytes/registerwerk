# eWpG Registry

A reference implementation for an electronic-securities registry built with Spring Boot, Angular, Foundry smart contracts, and Kong API gateway. The repository is not evidence of eWpG compliance, regulatory authorisation, certification, or production readiness; those conclusions require an operator-, instrument-, jurisdiction-, and deployment-specific review.

## Architecture

**You open both frontends directly in the browser — `:44200` and `:44201` are not going away
in favor of Kong.** Kong (published on `:48000`) is an API gateway, not a frontend reverse proxy: it fronts
*only* the customer frontend's backend API calls, and even that only because the customer
frontend's own nginx forwards `/api/` to Kong rather than to the backend directly. The operator
frontend's nginx forwards `/api/` straight to `backend:8080` and never touches Kong at all — this
is intentional (see "Why two paths?" below), not a stopgap.

```
 ┌─────────────────────────────────────────────────────────────────────┐
 │                              Browser                                │
 └───────────────────┬───────────────────────────────┬─────────────────┘
                     │ http://localhost:44200         │ http://localhost:44201
              ┌──────▼──────┐                 ┌──────▼──────┐
              │  frontend-  │                 │  frontend-  │
              │  operator   │                 │  customer   │
              │ (Angular22) │                 │ (Angular22) │
              └──────┬──────┘                 └──────┬──────┘
                     │ nginx /api/ ->                │ nginx /api/ ->
                     │ backend:8080 directly         │ kong:8000
                     │ (bypasses Kong)               │
                     │                        ┌──────▼──────┐
                     │                        │    Kong     │  :48000 -> :8000 (proxy)
                     │                        │  API GW     │  :48001 -> :8001 (admin, loopback)
                     │                        └──────┬──────┘
                     │                               │
                     └───────────────┬───────────────┘
                              ┌──────▼──────┐
                              │  Backend    │  :48080 -> :8080
                              │ Spring Boot │
                              └──────┬──────┘
                    ┌────────────────┼─────────────────┐
              ┌─────▼─────┐   ┌──────▼──────┐   ┌──────▼──────┐
              │ PostgreSQL│   │  S3 / OBS   │   │zama-relayer │  :43005 -> :3001 (opt-in,
              │ :45432→5432│  │  (docs)     │   │(Zama fhEVM) │  `--profile confidential`)
              └───────────┘   └─────────────┘   └─────────────┘
                    │
        ┌───────────┼───────────┐
   ┌────▼───┐  ┌────▼───┐  ┌────▼────┐
   │  ETH   │  │ Polygon│  │ Base, … │  ← EVM chains (Web3j), incl. confidential (Zama fhEVM)
   └────────┘  └────────┘  └─────────┘
   ┌────────┐  ┌────────┐  ┌─────────┐
   │ Solana │  │Starknet│  │ Stellar │  ← Solanaj / native Cairo v3 / Horizon
   └────────┘  └────────┘  └─────────┘
        ┌──────────────────────┐
        │   Canton (Daml)      │  ← Daml Java bindings
        └──────────────────────┘
```

### Why two paths to the backend?

The **operator frontend** bypasses Kong entirely — it uses the built-in HS256 JWT login
(`POST /api/v1/public/auth/login`) and stays functional even if Kong is down. The **customer
frontend**'s API calls go through Kong, which applies rate limiting, response caching, and
security headers in front of the backend (JWT validation itself always happens in the Spring
backend, not Kong — see Security Notes below). Neither path changes how you *open* either
app — both are always reached directly at their own port.

## Quick Start

### Prerequisites

- Docker & Docker Compose
- JDK 25 (for local backend development; the build targets Java 25 and fails on older JDKs)
- Node 24 / npm (for frontend development)
- Foundry (`curl -L https://foundry.paradigm.xyz | bash`)

### 1. Copy environment file

```bash
# For demo/test deployments
cp .env.example.test .env

# For production-like/mainnet deployments
# cp .env.example .env
```

For demo mode (`ENTRA_ENABLED=false`), set:

```dotenv
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=replace-me
```

### 2. Start all services

The test template enables the Chaincache showcase. Provide its independently built or distributed
image under the configured tag before starting Registerwerk; Registerwerk does not need a sibling
checkout and never builds `../chaincache`:

```bash
# A registry pull or `docker load` works equally well.
docker build -t registerwerk-chaincache:latest ../chaincache
```

```bash
docker compose up -d
```

This already builds and starts **both frontends** as Docker services, so you can open them
immediately — no separate `npm start` needed:
- Operator portal: http://localhost:44200
- Customer portal: http://localhost:44201
- Backend API: http://localhost:48080/swagger-ui.html
- Kong proxy (customer API traffic only): http://localhost:48000
- Kong admin API (loopback only, no GUI): http://localhost:48001 — reach it via `docker exec` or an SSH tunnel, never expose it publicly
- Chaincache workloads when `CHAINCACHE_ENABLED=true` (one Spring Boot process per chain,
  loopback-only — see [chaincache Integration](docs/operator/blockchain/chaincache-integration.md)):
  `chaincache-sepolia` on http://localhost:48090/sepolia/rpc and `chaincache-base` on
  http://localhost:48091/base/rpc. Their shared Postgres dependency is a private service in
  this same Compose project.
- Confidential (Zama fhEVM) token support is opt-in: `docker compose --profile confidential up -d` also starts `zama-relayer` on http://localhost:43005

### 3. Frontend development with hot-reload

For local iteration on either Angular app instead of rebuilding the Docker image each time:

```bash
# Operator frontend
cd frontend-operator && npm install && npm start
# → http://localhost:44200 (stop the frontend-operator container first to free the port)

# Customer frontend
cd frontend-customer && npm install && npm start
# → http://localhost:44201 (stop the frontend-customer container first to free the port)
```

### 4. Build & test the backend

```bash
cd backend
./mvnw verify
# JaCoCo coverage report: target/site/jacoco/index.html

# Integration tests use Testcontainers + Docker. On OrbStack (macOS):
# export DOCKER_HOST=unix:///var/run/docker.sock

# Canton/DAML support (requires JFrog credentials in ~/.m2/settings.xml):
# ./mvnw verify -Pcanton
```

### 5. Smart contract tests

```bash
cd contracts
forge install   # install submodules (forge-std, openzeppelin, erc3643)
forge build
forge test -vvv

# Or via Maven:
cd backend && ./mvnw verify -Pcontracts
```

### 6. Cairo & Daml contracts

```bash
# Starknet contracts (requires scarb + starknet-foundry)
cd contracts/cairo && scarb build && snforge test

# Canton bond templates (requires dpm, https://docs.digitalasset.com)
cd daml && dpm build
```

## Project Structure

```
registerwerk/
├── backend/                  Spring Boot 4.1 / JDK 25 — Spring Modulith 2.1 bounded-context architecture
│   └── src/main/java/de/makibytes/registerwerk/   (39 Spring Modulith modules)
│       ├── accessreview/     Periodic access recertification (reviews, write-once decisions, SoD warnings)
│       ├── admin/            Operator user management + impersonation
│       ├── asset/            Securities (assets, deployments, term sheets, holders)
│       ├── audit/            Append-only, hash-chained, signed audit log (event-driven via @ApplicationModuleListener)
│       ├── auth/             JWT minting, user auth, login throttle, sessions
│       ├── blockchain/       RPC registry, EVM/Solana/Starknet/Stellar deployment, outbox, token admin
│       ├── bootstrap/        Demo-data seeders (never active in production mode)
│       ├── chain/            Chain/network config, RPC node health
│       ├── corporateactions/ Coupons, redemptions, record-date snapshots, income statements
│       ├── customer/         Legal entities, KYB, company users, entity tasks, reinstatement
│       ├── deployment/       On-chain state: deployments, bond terms, holders, vault, mint, register units
│       ├── dora/             DORA incidents, ICT providers, resilience tests
│       ├── endpoint/         Risk-scored counterparty wallet address register
│       ├── entra/            Microsoft Graph two-factor status for Entra users
│       ├── erc3643/          ERC-3643 (T-REX) compliance suite
│       ├── externalref/      External system ID mapping
│       ├── finality/         Block finality, chain-effect journal, compensation
│       ├── idempotency/      Idempotency-Key handling
│       ├── indexer/          Off-chain event sync (EVM/Solana/Canton)
│       ├── infrastructure/   Cross-cutting configuration, scheduled maintenance, retention sweeps
│       ├── kyc/              KYC document management + jurisdiction approvals
│       ├── lending/          Isolated lending markets (release-gated)
│       ├── marketplace/      dApp marketplace: manifests, review, onchain anchoring
│       ├── notification/     Email notification listeners (event-driven)
│       ├── onboarding/       Customer onboarding flow
│       ├── orgidentity/      Onchain org identity, wallet binding, permissions
│       ├── payment/          Operator-curated payment rail catalog (DvP cash leg)
│       ├── registerstatement/Annual and on-demand register statements
│       ├── registertransfer/ Register handover to a successor registrar
│       ├── regreporting/     MiFIR / DAC8 regulatory reporting (prototype)
│       ├── repo/             Repo desk (GMRA records, margin, default workflow; release-gated)
│       ├── screening/        Sanctions/PEP screening (pluggable port)
│       ├── shared/           Cross-cutting exceptions and helpers (ProductionMode, Money, RegisterClock)
│       ├── stepup/           Step-up MFA, dual control, in-app approval queue
│       ├── support/          Customer support tickets
│       ├── trading/          Trade listings + executions
│       ├── travelrule/       Travel Rule messaging + CASP authorization register
│       ├── wallet/           Operator wallets: keystores, HSM, cloud-KMS signer, KEK rotation
│       ├── webhook/          Outbound webhook delivery
├── contracts/                Foundry smart contracts
│   └── src/
│       ├── tokens/           EwpgERC20, ERC721, ERC1155, ERC3643 (T-REX)
│       ├── compliance/       EwpgCompliance, WhitelistRegistry, MintController
│       ├── confidential/     ConfidentialERC20, ConfidentialERC3643 (Zama fhEVM)
│       ├── ecosystem/        OrgRegistry, PermissionRegistry, DappRegistry, PermissionOracle
│       └── factory/          AssetTokenFactory (CREATE2)
├── zama-relayer/             Node/TS sidecar wrapping @zama-fhe/relayer-sdk (opt-in, no Java SDK exists)
├── gateway/                  Kong declarative config + plugins
├── frontend-operator/        Angular 22 — registry operator UI
└── frontend-customer/        Angular 22 — issuer / investor UI
```

Each backend module follows the pattern `<module>/api/` (public surface), `<module>/internal/` (private), `<module>/events/` (typed domain events), `<module>/web/` (REST layer). See `docs/platform/modules.md` for the module reference and `CLAUDE.md` for the working conventions.

## Key Concepts

### Onchain Levels

| Level | Description |
|---|---|
| `NONE` | Only PostgreSQL; no blockchain interaction |
| `SIMPLE` | Asset token on-chain; issuer sends tokens to KYC'd & whitelisted investors |
| `CONTROL` | Adds contract-level compliance and mint-control mechanisms; legal/register authority remains instrument-specific |

### Token Standards

| Standard | Status |
|---|---|
| ERC-20 / ERC-721 / ERC-1155 | Repository implementation present; production readiness unverified |
| ERC-3643 (T-REX) | Repository implementation present; production readiness unverified |
| ERC-3525 (semi-fungible bonds) | Repository implementation present; production readiness unverified |
| ERC-4626 / ERC-7540 (tokenized vaults) | Repository implementation present; economic terms and production readiness unverified |
| Confidential ERC-20 / ERC-3643 (Zama fhEVM) | Repository implementation present; production readiness unverified |
| SPL / SPL-2022 (Solana) | Repository integration present; production readiness unverified |
| Starknet ERC-20 / ERC-3525 (Cairo) | Repository integration present; production readiness unverified |
| Stellar classic asset | Repository integration present; production readiness unverified |
| Custom Daml bond lifecycle (Canton) | Optional `-Pcanton` implementation; live participant/payment conformance still required |

### Supported Chains

| Chain | Mainnet | Testnet |
|---|---|---|
| Ethereum | chain-id 1 | Sepolia (11155111) |
| Polygon | chain-id 137 | Amoy (80002) |
| Base | chain-id 8453 | Base Sepolia (84532) |
| Arbitrum / Avalanche / Optimism | ✓ | ✓ |
| Fhenix | chain-id 21888 | Helium (8008135) |
| Inco | chain-id 9090 | Rivest (21097) |
| Solana | mainnet-beta | devnet |
| Starknet | SN_MAIN | SN_SEPOLIA |
| Stellar | pubnet | testnet |
| Canton | Canton Network | local participant |

### Roles

| Role | Permissions |
|---|---|
| `REGISTRY_ADMIN` | Full operator access; sharp operations also need a second approver (`REGISTRY_ADMIN` or `COMPLIANCE_OFFICER`) |
| `AUDIT` | Read all |
| `COMPLIANCE_OFFICER` | KYC approvals, screening reviews, holder blocks |
| `RELATIONSHIP_MANAGER` | Read and support assigned customer entities |
| `SUPPORT_AGENT` | Start read-only customer impersonation sessions (step-up and a recorded reason); nothing else |
| `ISSUER` | Own issuances (read + write) |
| `INVESTOR` | Own investments |
| `TRADER` | Secondary-market listings and executions |
| `COMPANY_ADMIN` | Manage own entity's users + IdP |
| `DAPP_PUBLISHER` | Submit and manage marketplace dApp listings |

## Onboarding Flow

1. Operator creates legal entity via `POST /api/v1/entities`
2. Operator generates token via `POST /api/v1/onboarding/tokens`
3. Token sent to entity's admin via email
4. Entity admin redeems token at `/onboarding/redeem/:token` in the customer frontend
5. Entity admin sets up users and optionally configures their own IdP
6. Users receive welcome email with frontend links and API docs URL

## API Documentation

The OpenAPI document and Swagger UI are off by default. Start the backend with `SWAGGER_ENABLED=true` to serve http://localhost:48080/swagger-ui.html (and `/api-docs`); they are unauthenticated while enabled, so keep them off on internet-facing deployments. Every route is also listed in the generated [API route index](docs/platform/api-routes.md).

## Product Documentation

The documentation server includes a fifth, optional Chaincache path sourced from a pinned
Chaincache submodule. Initialize it before building the docs image:

```bash
git submodule update --init docs/_chaincache
docker compose --profile docs up --build docs
```

Open the resulting site at http://localhost:48003. The Markdown under
`docs/_chaincache/docs/` remains owned by Chaincache; advance the submodule pointer when a newer
canonical documentation version should appear in Registerwerk.

## Database Migrations

Flyway migrations run automatically on startup. The scripts live in `backend/src/main/resources/db/migration/`; the directory is the list (this README deliberately carries no per-version table, because the baseline is re-squashed from time to time).

- A single clean-install baseline, `V1__initial_schema.sql`; later changes are added as `V{n}__description.sql`.
- Do not edit a migration after release.
- `scripts/check-destructive-migrations.sh` (wired into the backend CI workflow) rejects unguarded `DROP TABLE`, `DROP COLUMN` and `TRUNCATE`; acknowledge an intentional one with `-- migration-safety: ack (<why>)` directly above it.
- The runtime database login (`registerwerk_app`) is different from the migration/owner login; see the environment reference for `DB_APP_USER` and `SPRING_FLYWAY_USER`.

## Smart Contract Deployment

```bash
# Deploy to testnet
cd contracts
forge script script/DeployTestnet.s.sol --rpc-url sepolia --broadcast --verify

# Deploy to mainnet (after audit)
forge script script/Deploy.s.sol --rpc-url mainnet --broadcast --verify
```

The `AssetTokenFactory` uses `CREATE2` with a deterministic salt so contract addresses can be pre-computed by the backend before deployment.

## Security Notes

- The backend acts as an OAuth2 Resource Server in both modes; it validates the JWT itself and
  resolves the principal to an `app_user` row (`DefaultPrincipalResolver`). Persisted roles and
  entity scope are authoritative after provisioning, and inbound `X-Entity-Id`/`X-Entity-Roles`
  headers are not trusted. In OIDC mode, customer traffic
  typically flows through Kong first, but Kong here only adds rate limiting, response caching, and
  security headers; it does not itself validate the JWT (Kong's `openid-connect` plugin is
  Enterprise-only — see `gateway/plugins/oidc-entra.yml` for the config to merge in if you're
  running Kong Enterprise/Konnect instead of OSS).
- Dev/demo mode (`ENTRA_ENABLED=false`): backend mints HS256 JWTs via `POST /api/v1/public/auth/login`.
- Onboarding tokens are stored as SHA-256 hashes; the cleartext is sent once via email
- KYC documents ≤ 5 MB are stored as PostgreSQL `BYTEA` (in a separate `kyc_document_content` table); larger files are stored in S3
- In production the registry signing keys belong in a PKCS#11 HSM or a cloud-KMS signer (`REGISTERWERK_WALLET_SIGNER=kms`, GCP Cloud KMS); production mode refuses a software-only setup.
- Set `REGISTERWERK_PRODUCTION_MODE=true` on every production deployment: the readiness checks then refuse an unsafe configuration at start-up (see `docs/operator/security/production-mode.md`). Sharp operations require step-up and, for the listed ones, a second approver through the in-app approval queue. The application connects as a DML-only database role, separate from the migration/owner login.

## License

Proprietary — All rights reserved.
