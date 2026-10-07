---
title: Production mode and release gates
description: What REGISTERWERK_PRODUCTION_MODE changes, which start-up gates refuse an unsafe configuration, and the variables that acknowledge or release them.
---

# Production mode and release gates

Set `REGISTERWERK_PRODUCTION_MODE=true` (environment variable, `-Dregisterwerk.production-mode=true` or any Spring config source; resolved once by `shared.ProductionMode`) on every production deployment. Without it the checks below log warnings and the demo conveniences stay on. With it, an unsafe configuration **stops the application from starting**, and the production-only controls apply.

!!! warning "Passing these gates is not an attestation"
    The gates catch known-unsafe configuration. They do not establish that a deployment is legally compliant, authorised or operationally ready, and they do not replace your own release review.

## What else production mode changes

- Impersonation is **read-only** only; `ACT_ON_BEHALF` is refused and any live session is enforced read-only ([Impersonation](../customers/impersonation.md)).
- Lender-side supply positions need an eligible entity (professional or eligible-counterparty classification, active, approved and unexpired KYC, no unresolved screening hit, no Sperrvermerk); the Supply & Earn page hides the form for an ineligible entity. The contracts themselves are permissionless, so this protects the product surface only.
- Peer listings in secondary trading are refused unless the venue classification is changed from `DEMO_ONLY`.
- Forward-priced ERC-7540 vaults without a dealing cut-off refuse new subscriptions.
- Webhook destinations must be public HTTPS (see below); session cookies and CORS are hardened.
- Demo accounts, demo data seeders and demo wiring must not be present.

## Start-up gates

| Gate | Refused when (production mode) | Controlled by |
|---|---|---|
| JWT secret | `JWT_ISSUER_URI` blank and `JWT_DEV_SECRET` is the built-in default; secret shorter than 32 bytes | `JWT_ISSUER_URI`, `JWT_DEV_SECRET` |
| Entra | `ENTRA_ENABLED=true` without `JWT_ISSUER_URI`, `JWT_AUDIENCE`, `ENTRA_TENANT_ID` or `ENTRA_STEPUP_AUTH_CONTEXT_ID`; `ENTRA_SUPPORT_ENABLED=true` without client id and secret | `ENTRA_*`, `JWT_AUDIENCE` |
| Wallet keys | `REGISTERWERK_WALLET_MASTER_KEY` unset; KEK provider unset or `ENV_VAR` (must be `AWS_KMS`, `AZURE_KEY_VAULT` or `GCP_KMS`) | `REGISTERWERK_WALLET_MASTER_KEY`, `REGISTERWERK_WALLET_KEK_PROVIDER` |
| Signing custody | Neither a PKCS#11 HSM (`REGISTERWERK_HSM_ENABLED=true`) nor a cloud-KMS signer (`REGISTERWERK_WALLET_SIGNER=kms`); the SoftHSM profile without `registerwerk.wallet.hsm.allow-software-token=true`; the published demo HSM PIN | `REGISTERWERK_HSM_*`, `REGISTERWERK_WALLET_SIGNER` |
| Cloud-KMS signer | Provider unsupported, key version missing or malformed, or the key does not answer | `registerwerk.wallet.kms.*` |
| Step-up | `registerwerk.auth.step-up.allow-unenrolled=true` (bypasses the TOTP second factor) | `registerwerk.auth.step-up.allow-unenrolled` |
| Admin account | `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` unset or the published example password; the seeded administrator still has its initial password 24 hours after seeding while it is the only usable administrator | `DEFAULT_ADMIN_*` |
| Demo data | `SEED_DEMO_DATA=true`; demo seeder beans registered; `registerwerk.trading.demo-instant-settlement=true` | `SEED_DEMO_DATA`, `registerwerk.trading.demo-instant-settlement` |
| Database logins | Blank or default passwords (`changeme`, `registerwerk`, `registerwerk_app`) for `DB_PASSWORD` or `SPRING_FLYWAY_PASSWORD`; `DB_APP_USER` equal to `SPRING_FLYWAY_USER` | `DB_*`, `DB_APP_*`, `SPRING_FLYWAY_*` |
| Published defaults | `ZAMA_RELAYER_API_KEY` or `CHAINCACHE_JWT_SECRET` still at the published default; CORS origins that are all localhost | `ZAMA_RELAYER_API_KEY`, `CHAINCACHE_JWT_SECRET`, `REGISTERWERK_CORS_ALLOWED_ORIGINS` |
| Audit log | No signing key provider; the runtime login owns `audit_event`, or a non-owner runtime login still holds `UPDATE`/`DELETE`/`TRUNCATE` on the audit tables or `CREATE` on the schema | `REGISTERWERK_AUDIT_SIGNING_PROVIDER` (`ENV_VAR` for dev/test only, `GCP_KMS` for real signing), `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE` (acknowledges the owner case only) |
| Trading | `demo-instant-settlement=true`; a non-`DEMO_ONLY` venue classification without a legal-opinion reference | `REGISTERWERK_TRADING_VENUE_CLASSIFICATION`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF` |
| Travel Rule | Own VASP identity unset; insecure TRP endpoints; the legacy shared key; Notabene without key and DID; TRP without endpoint and mTLS material; protocol not `TRP` or `NOTABENE` | `REGISTERWERK_TRAVEL_RULE_*` |
| Regulatory reporting | The draft MiFIR/DAC8 prototype is enabled | `REGISTERWERK_REPORTING_PROTOTYPE_ENABLED` |
| Lending and repo | `registerwerk.lending.enabled=true` without `release-approved=true`; the same pair guards the Repo Desk | `REGISTERWERK_LENDING_ENABLED` / `_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_ENABLED` / `_RELEASE_APPROVED` |
| Webhooks | `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS=true` (plain HTTP, loopback and private targets) | `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS` |

Two checks only warn and never block start-up: the ERC-3643 ClaimIssuer manager check (logs an error and raises a gauge when the hot registry signer is also a MANAGEMENT key of a ClaimIssuer; the accepted default, which must be backed by HSM/KMS custody of that key) and the vault dealing cut-off check (lists vaults that will refuse subscriptions until `POST /api/v1/deployments/{id}/dealing-cutoff` is set).

## Variables that are safe-by-default and what loosening them means

| Variable | Default | Meaning |
|---|---|---|
| `SWAGGER_ENABLED` | `false` | Serves the OpenAPI document and Swagger UI **without authentication**. Nothing refuses it in production mode; keep it off ([REST API overview](../../platform/api.md#openapi-swagger-ui)) |
| `LINK_BY_EMAIL_WITHOUT_VERIFICATION` | blank | Links an IdP identity to an existing account by e-mail even when the token does not assert a verified address. Blank means allowed outside production mode and refused in production mode |
| `CLAIMS_ALLOW_UNAPPROVED_IN_NONPROD` | `false` | Break-glass for issuing on-chain claims to a non-approved entity; honoured only outside production profiles |
| `SPERRVERMERK_AUTO_EXPIRE_TYPES` | empty | Block types the daily job may lift automatically after their expiry date; by default none, so an expired block goes to `EXPIRY_REVIEW` and keeps blocking |
| `REGISTERWERK_PAYMENT_ONCHAIN_VERIFY` | `true` | Verifies chain-bound payment rails against the chain when they are saved (address holds code, stablecoin decimals match); keep it on in production |
| `REGISTERWERK_AUDIT_ANCHOR_SINK` | `none` | `s3` publishes the daily audit anchor to an Object Lock bucket (see [Audit log](../../platform/audit-log.md)) |
| `REGISTERWERK_WALLET_SIGNER` | blank | `kms` selects cloud-KMS signing ([HSM-backed wallets](hsm-wallets.md)) |

See also [Environment variables](../configuration/environment.md), the [operational settings reference](../configuration/environment-settings.md), the [step-up matrix](../../compliance/step-up-matrix.md), the [approval queue](approval-queue.md) and the [open decisions and known limitations](../../platform/known-limitations.md).
