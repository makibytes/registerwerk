# eWpG Registry

A German Electronic Securities Act (eWpG) compliant crypto asset registry built with Spring Boot, Angular, Foundry smart contracts, and Kong API gateway.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  frontend-operator (:4200)    frontend-customer (:4201)         │
│       (Angular 21)                  (Angular 21)                │
└──────────────────────────┬──────────────────────────────────────┘
                ┌──────────┴──────────┐
                │                     │
                │                     │
         (operator frontend)   (customer frontend)
         nginx -> backend      nginx -> Kong -> backend
                │                     │
                │                     │
                    ┌──────▼──────┐
                    │    Kong     │  :8000 (proxy)
                    │  API GW     │  :8001 (admin)
                    │  + OIDC     │  :1337 (Konga UI)
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  Backend    │  :8080
                    │ Spring Boot │
                    └──────┬──────┘
                    ┌──────┴──────┐
              ┌─────▼─────┐ ┌────▼────┐
              │ PostgreSQL│ │ S3 / OBS│
              │  :5432    │ │(docs)   │
              └───────────┘ └─────────┘
                    │
        ┌───────────┼───────────┐
   ┌────▼───┐  ┌────▼───┐ ┌────▼────┐
   │  ETH   │  │ Polygon│ │  Base   │  ← EVM chains (Web3j)
   └────────┘  └────────┘ └─────────┘
        ┌──────────────────────┐
        │       Solana         │  ← Solanaj
        └──────────────────────┘
```

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21+ (for local backend development)
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

Services:
- Backend API: http://localhost:8080/swagger-ui.html
- Kong proxy: http://localhost:8000
- Kong admin: http://localhost:8001
- Konga UI: http://localhost:1337

### 3. Run the frontends (development)

```bash
# Operator frontend
cd frontend-operator && npm install && npm start
# → http://localhost:4200

# Customer frontend
cd frontend-customer && npm install && npm start
# → http://localhost:4201
```

### 4. Build & test the backend

```bash
cd backend
./mvnw verify
# JaCoCo coverage report: target/site/jacoco/index.html
```

### 5. Smart contract tests

```bash
cd contracts
forge install   # install submodules (forge-std, openzeppelin, erc3643)
forge build
forge test -vvv
```

## Project Structure

```
registerwerk/
├── backend/                  Spring Boot 4 / Java 25 monolith
│   └── src/main/java/de/makibytes/registerwerk/
│       ├── config/           Security, Cache, Web, Blockchain config
│       ├── domain/           JPA entities + enums (no Spring deps)
│       ├── application/      Use-case services + blockchain adapters
│       ├── infrastructure/   JPA repos, S3, Web3j, Solana, SMTP
│       └── web/              REST controllers, DTOs, MapStruct mappers
├── contracts/                Foundry smart contracts
│   └── src/
│       ├── tokens/           EwpgERC20, ERC721, ERC1155, ERC3643 (T-REX)
│       ├── compliance/       EwpgCompliance, WhitelistRegistry, MintController
│       ├── confidential/     ConfidentialERC20, ConfidentialERC3643 (Zama fhEVM)
│       └── factory/          AssetTokenFactory (CREATE2)
├── gateway/                  Kong declarative config + plugins
├── frontend-operator/        Angular 21 — registry operator UI
└── frontend-customer/        Angular 21 — issuer / investor UI
```

## Key Concepts

### Onchain Levels

| Level | Description |
|---|---|
| `NONE` | Only PostgreSQL; no blockchain interaction |
| `SIMPLE` | Asset token on-chain; issuer sends tokens to KYC'd & whitelisted investors |
| `CONTROL` | On-chain is primary layer; mint control allowances and auto-approval for `transferFrom`/`burn` |

### Token Standards

| Standard | Status |
|---|---|
| ERC-20 | Fully implemented |
| ERC-721 | Fully implemented |
| ERC-1155 | Fully implemented |
| ERC-3643 (T-REX) | Fully implemented |
| Confidential ERC-20 (Zama fhEVM) | Fully implemented |
| Confidential ERC-3643 (Zama fhEVM) | Fully implemented |
| SPL (Solana) | Implemented via Solanaj |

### Supported Chains

| Chain | Mainnet | Testnet |
|---|---|---|
| Ethereum | chain-id 1 | Sepolia (11155111) |
| Polygon | chain-id 137 | Amoy (80002) |
| Base | chain-id 8453 | Base Sepolia (84532) |
| Fhenix | chain-id 21888 | Helium (8008135) |
| Inco | chain-id 9090 | Rivest (21097) |
| Solana | mainnet-beta | devnet |

### Roles

| Role | Permissions |
|---|---|
| `REGISTRY_ADMIN` | Full access |
| `AUDIT` | Read all |
| `ISSUER` | Own issuances (read + write) |
| `INVESTOR` | Own investments |
| `COMPANY_ADMIN` | Manage own entity's users + IdP |

## Onboarding Flow

1. Operator creates legal entity via `POST /api/v1/entities`
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

- The backend acts as an OAuth2 Resource Server in both modes.
- OIDC mode: JWTs are validated from `JWT_ISSUER_URI` and customer traffic typically flows through Kong.
- Dev/demo mode (`ENTRA_ENABLED=false`): backend mints HS256 JWTs via `POST /api/v1/public/auth/login`.
- Kong injects `X-Entity-Id` and `X-Entity-Roles` headers after validating the JWT; the backend trusts these headers only from Kong's internal network
- Onboarding tokens are stored as SHA-256 hashes; the cleartext is sent once via email
- KYC documents ≤ 5 MB are stored as PostgreSQL `BYTEA` (in a separate `kyc_document_content` table); larger files are stored in S3
- The registry backend wallet private key should be stored in a hardware security module (HSM) in production

## License

Proprietary — All rights reserved.
