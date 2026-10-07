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
| `DB_PASSWORD` | — | **Required** (the migration/owner login's password) |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `DB_USER` / `DB_PASSWORD` | Runtime login the application connects as (`registerwerk_app` in the shipped Compose and Helm setups). It must differ from the owner login in production mode, because the application must not own the audit tables |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | Migration/owner login used only by Flyway (set to `DB_USER`/`DB_PASSWORD` in Compose and Helm) |

## Authentication

### Built-in admin (no-IdP mode)

| Variable | Default | Description |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → username/password form in operator FE; `true` → Microsoft button |
| `DEFAULT_ADMIN_EMAIL` | — | Email of the seeded admin user (built-in mode only) |
| `DEFAULT_ADMIN_PASSWORD` | — | Plaintext password hashed with BCrypt when the admin is created; used only if no `REGISTRY_ADMIN` exists, never re-applied to an existing account |
| `JWT_DEV_SECRET` | built-in | HS256 signing key used in dev/demo mode; leave unset for local, override in staging |

### OAuth2 / OIDC (production)

| Variable | Description |
|---|---|
| `JWT_ISSUER_URI` | OIDC issuer URL — leave blank for HS256 dev mode; set for production (e.g. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | Client id of the API app registration; used with the secret for app-only Microsoft Graph access (two-factor status, support console). Not used by Kong, which does no OIDC |
| `ENTRA_CLIENT_SECRET` | Client secret of the API app registration (app-only Graph credential; required when `ENTRA_SUPPORT_ENABLED=true`) |

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

## Production mode and release gates

Set `REGISTERWERK_PRODUCTION_MODE=true` on every production deployment. It turns the readiness checks from warnings into start-up refusals and enables the production-only controls. [Production mode and release gates](../security/production-mode.md) lists every gate, the variable that controls it and what is refused. That page also documents the release-gate and acknowledgement variables (`REGISTERWERK_LENDING_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_RELEASE_APPROVED`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF`, `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE`, `REGISTERWERK_AUDIT_SIGNING_PROVIDER`, `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS`, `REGISTERWERK_WALLET_MASTER_KEY`, `LINK_BY_EMAIL_WITHOUT_VERIFICATION`, `SWAGGER_ENABLED` and others).

## More settings

The register calendar, chain confirmations, sign-in throttle, trading, Travel Rule, reporting, audit-anchor and signing-custody settings, with their defaults and what production mode expects, are in the [Operational settings reference](environment-settings.md).
