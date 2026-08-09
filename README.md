# eWpG Registry

A reference implementation for an electronic-securities registry built with Spring Boot, Angular, Foundry smart contracts, and Kong API gateway. The repository is not evidence of eWpG compliance, regulatory authorisation, certification, or production readiness; those conclusions require an operator-, instrument-, jurisdiction-, and deployment-specific review.

## Architecture

**You open both frontends directly in the browser — `:4200` and `:4201` are not going away
in favor of Kong.** Kong (`:8000`) is an API gateway, not a frontend reverse proxy: it fronts
*only* the customer frontend's backend API calls, and even that only because the customer
frontend's own nginx forwards `/api/` to Kong rather than to the backend directly. The operator
frontend's nginx forwards `/api/` straight to `backend:8080` and never touches Kong at all — this
is intentional (see "Why two paths?" below), not a stopgap.

```
 ┌─────────────────────────────────────────────────────────────────────┐
 │                              Browser                                │
 └───────────────────┬───────────────────────────────┬─────────────────┘
                     │ http://localhost:4200         │ http://localhost:4201
              ┌──────▼──────┐                 ┌──────▼──────┐
              │  frontend-  │                 │  frontend-  │
              │  operator   │                 │  customer   │
              │ (Angular21) │                 │ (Angular21) │
              └──────┬──────┘                 └──────┬──────┘
                     │ nginx /api/ ->                │ nginx /api/ ->
                     │ backend:8080 directly         │ kong:8000
                     │ (bypasses Kong)               │
                     │                        ┌──────▼──────┐
                     │                        │    Kong     │  :8000 (proxy)
                     │                        │  API GW     │  :8001 (admin, loopback only)
                     │                        └──────┬──────┘
                     │                               │
                     └───────────────┬───────────────┘
                              ┌──────▼──────┐
                              │  Backend    │  :8080
                              │ Spring Boot │
                              └──────┬──────┘
                    ┌────────────────┼─────────────────┐
              ┌─────▼─────┐   ┌──────▼──────┐   ┌──────▼──────┐
              │ PostgreSQL│   │  S3 / OBS   │   │zama-relayer │  :3005 (opt-in,
              │  :5432    │   │  (docs)     │   │(Zama fhEVM) │  `--profile confidential`)
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
- Java 25+ (for local backend development)
- Node 20+ / npm (for frontend development)
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

```bash
docker compose up -d
```

This already builds and starts **both frontends** as Docker services, so you can open them
immediately — no separate `npm start` needed:
- Operator portal: http://localhost:4200
- Customer portal: http://localhost:4201
- Backend API: http://localhost:8080/swagger-ui.html
- Kong proxy (customer API traffic only): http://localhost:8000
- Kong admin API (loopback only, no GUI): http://localhost:8001 — reach it via `docker exec` or an SSH tunnel, never expose it publicly
- Confidential (Zama fhEVM) token support is opt-in: `docker compose --profile confidential up -d` also starts `zama-relayer` on http://localhost:3005

### 3. Frontend development with hot-reload

For local iteration on either Angular app instead of rebuilding the Docker image each time:

```bash
# Operator frontend
cd frontend-operator && npm install && npm start
# → http://localhost:4200 (stop the frontend-operator container first to free the port)

# Customer frontend
cd frontend-customer && npm install && npm start
# → http://localhost:4201 (stop the frontend-customer container first to free the port)
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
├── backend/                  Spring Boot 4.1 / Java 25 — Spring Modulith 2.1 bounded-context architecture
│   └── src/main/java/de/makibytes/registerwerk/
│       ├── admin/            Operator user management + impersonation
│       ├── asset/            Securities (assets, deployments, term sheets, holders)
│       ├── audit/            Append-only audit log (event-driven via @ApplicationModuleListener)
│       ├── auth/             JWT minting, user auth, onboarding tokens
│       ├── blockchain/       RPC registry, EVM/Solana/Starknet/Stellar deployment, token admin
│       ├── chain/            Chain/network config, RPC node health
│       ├── customer/         Legal entities, KYB, company users
│       ├── deployment/       On-chain state: deployments, bond terms, holders, vault, mint
│       ├── erc3643/          ERC-3643 (T-REX) compliance suite
│       ├── externalref/      External system ID mapping
│       ├── indexer/          Off-chain event sync (EVM/Solana/Canton)
│       ├── kyc/              KYC document management + jurisdiction approvals
│       ├── marketplace/      dApp marketplace: manifests, review, onchain anchoring
│       ├── notification/     Email notification listeners (event-driven)
│       ├── onboarding/       Customer onboarding flow
│       ├── orgidentity/      Onchain org identity, wallet binding, permissions
│       ├── payment/          Operator-curated payment rail catalog (DvP cash leg)
│       ├── screening/        Sanctions/PEP screening (pluggable port)
│       ├── shared/           Cross-cutting exceptions, utilities
│       ├── stepup/           Step-up MFA, 4-eyes enforcement
│       ├── trading/          Trade listings + executions
│       └── wallet/           Operator wallet management
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

Each backend module follows the pattern `<module>/api/` (public surface), `<module>/internal/` (private), `<module>/events/` (typed domain events), `<module>/web/` (REST layer). See `CLAUDE.md` for the current full module list — it changes faster than this README.

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
| Starknet ERC-20 / ERC-3525 (Cairo) | Placeholder / incomplete; do not use in production |
| Stellar classic asset | Placeholder / incomplete; do not use in production |
| Daml Finance bonds (Canton) | Optional repository implementation (`-Pcanton`); production readiness unverified |

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
| `REGISTRY_ADMIN` | Full access |
| `AUDIT` | Read all |
| `COMPLIANCE_OFFICER` | KYC approvals, screening reviews, holder blocks |
| `ISSUER` | Own issuances (read + write) |
| `INVESTOR` | Own investments |
| `TRADER` | Secondary-market listings and executions |
| `COMPANY_ADMIN` | Manage own entity's users + IdP |
| `DAPP_PUBLISHER` | Submit and manage marketplace dApp listings |

## Onboarding Flow

1. Operator creates legal entity via `POST /api/v1/customers`
2. Operator generates token via `POST /api/v1/onboarding/tokens`
3. Token sent to entity's admin via email
4. Entity admin redeems token at `/onboarding/redeem/:token` in the customer frontend
5. Entity admin sets up users and optionally configures their own IdP
6. Users receive welcome email with frontend links and API docs URL

## API Documentation

Once the backend is running: http://localhost:8080/swagger-ui.html

## Database Migrations

Flyway migrations run automatically on startup. Scripts in `backend/src/main/resources/db/migration/`:

| Version | Description |
|---|---|
| V1 | Initial schema — legal entities, KYC, onboarding tokens, assets and deployments, holders, mint control, partitioned audit log, entity merges, chain registry (incl. Fhenix/Inco), token transfer history, indexer state, ONCHAINID, ERC-3643 (T-REX) suites and identity registry mirror |

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
  derives entity/role authorities directly from its claims (`JwtEntityClaimsConverter`) — it does
  not trust any inbound `X-Entity-Id`/`X-Entity-Roles` headers. In OIDC mode, customer traffic
  typically flows through Kong first, but Kong here only adds rate limiting, response caching, and
  security headers; it does not itself validate the JWT (Kong's `openid-connect` plugin is
  Enterprise-only — see `gateway/plugins/oidc-entra.yml` for the config to merge in if you're
  running Kong Enterprise/Konnect instead of OSS).
- Dev/demo mode (`ENTRA_ENABLED=false`): backend mints HS256 JWTs via `POST /api/v1/public/auth/login`.
- Onboarding tokens are stored as SHA-256 hashes; the cleartext is sent once via email
- KYC documents ≤ 5 MB are stored as PostgreSQL `BYTEA` (in a separate `kyc_document_content` table); larger files are stored in S3
- The registry backend wallet private key should be stored in a hardware security module (HSM) in production

## License

Proprietary — All rights reserved.
