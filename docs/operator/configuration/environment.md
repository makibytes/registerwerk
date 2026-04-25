---
id: environment
title: Environment Variables
sidebar_position: 1
---

# Environment Variables

All configuration is done via environment variables. Copy `.env.example` to `.env` and fill in values.

## Database

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `ewpg_registry` | Database name |
| `DB_USER` | `ewpg` | Database user |
| `DB_PASSWORD` | — | **Required** |

## Authentication (OAuth2)

| Variable | Description |
|---|---|
| `JWT_ISSUER_URI` | OIDC issuer URL (e.g. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `JWT_AUDIENCE` | Expected audience claim value |

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
| `FHENIX_MAINNET_RPC` | Fhenix Mainnet (FHE) |
| `FHENIX_HELIUM_RPC` | Fhenix Helium testnet |
| `INCO_MAINNET_RPC` | Inco Mainnet (FHE) |
| `INCO_RIVEST_RPC` | Inco Rivest testnet |
| `DEPLOYER_PRIVATE_KEY` | Wallet key for contract deployments |

## Storage

| Variable | Description |
|---|---|
| `AWS_REGION` | S3 region (or MinIO region) |
| `AWS_S3_BUCKET` | S3 bucket name for KYC documents |
| `AWS_ACCESS_KEY_ID` | S3 access key |
| `AWS_SECRET_ACCESS_KEY` | S3 secret key |

Documents smaller than 5 MB are stored inline as BYTEA in PostgreSQL. Documents ≥5 MB are stored in S3.

## Email

| Variable | Description |
|---|---|
| `MAIL_HOST` | SMTP host |
| `MAIL_PORT` | SMTP port (default 587) |
| `MAIL_USERNAME` | SMTP username |
| `MAIL_PASSWORD` | SMTP password |
| `MAIL_FROM` | Sender address |

## Onboarding

| Variable | Description |
|---|---|
| `CUSTOMER_FRONTEND_URL` | Base URL of the customer frontend (for email links) |
| `REGISTERWERK_ONBOARDING_TOKEN_TTL_HOURS` | Token expiry (default 48) |
