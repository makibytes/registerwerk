---
title: Environment Variables
---

# Environment Variables

All configuration is done via environment variables. Copy `.env.example` to `.env` and fill in values.

## Database

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | JDBC connection URL |
| `DB_USER` | `registerwerk` | Database user |
| `DB_PASSWORD` | — | **Required** |

## Authentication

### Built-in admin (no-IdP mode)

| Variable | Default | Description |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → username/password form in operator FE; `true` → Microsoft button |
| `DEFAULT_ADMIN_EMAIL` | — | Email of the seeded admin user (built-in mode only) |
| `DEFAULT_ADMIN_PASSWORD` | — | Plaintext password hashed with BCrypt on startup; rotate by changing and restarting |
| `JWT_DEV_SECRET` | built-in | HS256 signing key used in dev/demo mode; leave unset for local, override in staging |

### OAuth2 / OIDC (production)

| Variable | Description |
|---|---|
| `JWT_ISSUER_URI` | OIDC issuer URL — leave blank for HS256 dev mode; set for production (e.g. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | OIDC client id used by Kong plugin |
| `ENTRA_CLIENT_SECRET` | OIDC client secret used by Kong plugin |
| `ENTRA_ISSUER` | OIDC issuer configured in Kong plugin |

## Blockchain RPCs

| Variable | Chain |
|---|---|
| `ETH_MAINNET_RPC` | Ethereum Mainnet |
| `ETH_SEPOLIA_RPC` | Ethereum Sepolia |
| `POLYGON_MAINNET_RPC` | Polygon Mainnet |
| `POLYGON_AMOY_RPC` | Polygon Amoy |
| `BASE_MAINNET_RPC` | Base Mainnet |
| `BASE_SEPOLIA_RPC` | Base Sepolia |
| `SOLANA_MAINNET_RPC` | Solana Mainnet |
| `SOLANA_DEVNET_RPC` | Solana Devnet |
| `REGISTRY_WALLET_PRIVATE_KEY` | Backend signer key for blockchain operations |
| `REGISTRY_SOLANA_PRIVATE_KEY` | Optional Solana signer key |

## Storage

| Variable | Description |
|---|---|
| `S3_BUCKET` | S3 bucket name for KYC documents |
| `S3_ENDPOINT` | S3-compatible endpoint URL |
| `S3_ACCESS_KEY` | S3 access key |
| `S3_SECRET_KEY` | S3 secret key |
| `S3_REGION` | S3 region |

Documents smaller than 5 MB are stored inline as BYTEA in PostgreSQL. Documents ≥5 MB are stored in S3.

## Email

| Variable | Description |
|---|---|
| `MAIL_HOST` | SMTP host |
| `MAIL_PORT` | SMTP port (default 587) |
| `MAIL_USERNAME` | SMTP username |
| `MAIL_PASSWORD` | SMTP password |

## Onboarding

| Variable | Description |
|---|---|
| `CUSTOMER_FRONTEND_URL` | Base URL of the customer frontend (for email links) |
| `FRONTEND_BUILD_ENV` | Frontend build target: `production` or `testnet` |
