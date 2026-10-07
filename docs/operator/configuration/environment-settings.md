---
title: Operational settings reference
description: Environment variables and properties beyond the basics — register calendar, chain confirmations, sign-in throttle, trading, Travel Rule, reporting, audit anchor, signing custody — with defaults and production expectations.
---

# Operational settings reference

[Environment variables](environment.md) covers the basics (database, authentication, RPC, storage, e-mail). This page lists the other settings an operator needs to know, taken from the backend's `application.yml` placeholders. Every name here is an environment variable unless it is written as a dotted property; a property can be set as an environment variable by upper-casing it, replacing dots with underscores and dropping dashes (`registerwerk.retention.login-attempt.max-age` becomes `REGISTERWERK_RETENTION_LOGINATTEMPT_MAXAGE`).

The **Production** column says what production mode ([release gates](../security/production-mode.md)) does with the setting, or what to do. "Refused" means the application does not start. "Review" means the default is a demo or interim value that you should decide on yourself.

## Register calendar and holder sync

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTER_TIME_ZONE` | `Europe/Berlin` | Zone in which the register's calendar days are counted: record and payment dates, the record-date cut-off (entitlements are fixed as of the end of the record date in this zone) and the cron triggers of the corporate-action jobs | Review. Changing it moves cut-offs |
| `REGISTER_SNAPSHOT_SYNC_MARGIN` | `PT30M` | A chain-deployed register is snapshotted only after a successful holder sync at least this long after the record-date cut-off, so the indexer has provably seen the chain past it | Keep |
| `SYNC_INTERVAL_MINUTES` | `15` | Automatic holder sync from the chain (`0` disables it) | Keep enabled |
| `SYNC_BATCH_SIZE` | `50` | Issuances synced per scheduler run | Tune |
| `SYNC_LOG_OPERATIONS` | `false` | Verbose sync logging | Off |
| `SYNC_NOMINEE_POOL_ENTITY_ID` | blank | Legal entity (`legal_entity.id`) in which nominee-pool holder rows for pool contracts (lending markets, DvP escrow, desk, facility) are held. Blank means lending markets are not auto-registered and their collateral assets are not tracked | Set if you run lending or repo |
| `registerwerk.chain.destination-gate.enabled` (`DESTINATION_GATE_ENABLED`) | `true` | Whitelist, mint, forced transfer and forced approve destinations must be an active, KYC-approved, screened holder of the asset | Keep `true`. Disable only in a demo that mints to arbitrary addresses |

## Chain transactions, indexing and chaincache

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_TX_DEFAULT_CONFIRMATIONS` | `12` | Blocks before a receipt is trusted as `SUCCESS`/`FAILED`, so a shallow reorg cannot leave the register asserting a dropped state. Per-chain values (Polygon 128, Base 30, Arbitrum 20, Optimism 30, Avalanche 12) are the property `registerwerk.blockchain.tx.confirmations-by-chain.<CHAIN>` | Keep or raise |
| `REGISTERWERK_TX_TIMEOUT_SECONDS` | `900` | An un-mined transaction times out after this; a mined but shallow one never does | Keep |
| `REGISTERWERK_TX_LATE_MINED_WINDOW_SECONDS` | `604800` | Timed-out transactions keep being polled this long and complete when a receipt arrives | Keep |
| `REGISTERWERK_TX_REPLACED_CONFIRMATION_SECONDS` | `600` | How long the chain must report the nonce used before a transaction is declared `REPLACED` | Keep |
| `REGISTERWERK_TX_POLL_BATCH_SIZE` | `25` | Rows examined per poller run | Tune |
| `REGISTERWERK_DRIFT_CHECK_INTERVAL_MS` / `REGISTERWERK_DRIFT_BATCH_SIZE` | `900000` / `500` | Register versus chain drift check | Keep |
| `REGISTERWERK_INDEXER_COVERAGE_ENFORCE` | `true` | A deployment without a live, deployment-linked indexer blocks the holder sync ("deployment not indexed") instead of reconciling against an empty history | Must stay `true` |
| `REGISTERWERK_INDEXER_COVERAGE_MAX_STALENESS` | `PT2H` | How old the indexer's last block may be | Tune |
| `REGISTERWERK_CHAINCACHE_CONSUMER_GROUP` | `registerwerk` | Stable consumer identity across pod restarts; chaincache fences it with a database lease, so one replica consumes while the others wait | Keep |
| `CHAINCACHE_SUBSCRIBE_RESPONSE_TIMEOUT_MS` / `CHAINCACHE_ACK_RESPONSE_TIMEOUT_MS` | `10000` | A durable stream counts as usable only once every subscribe response and cursor acknowledgement has arrived | Keep |

### Additional RPC endpoints, Canton and Solana

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `ARBITRUM_MAINNET_RPC`, `ARBITRUM_SEPOLIA_RPC`, `AVALANCHE_MAINNET_RPC`, `AVALANCHE_FUJI_RPC`, `OPTIMISM_MAINNET_RPC`, `OPTIMISM_SEPOLIA_RPC`, `FHENIX_MAINNET_RPC`, `INCO_MAINNET_RPC`, `INCO_RIVEST_RPC` | public endpoints | RPC node per chain, like the ones in the basics page | Use a provider you have a contract with |
| `CANTON_APPLICATION_ID` | `registerwerk` | Ledger application id | Review |
| `CANTON_MAINNET_LEDGER_URL`, `CANTON_MAINNET_SYNCHRONIZER` | blank, `global-synchronizer` | Participant node and synchronizer for Canton mainnet | Set to use Canton |
| `CANTON_DEVNET_LEDGER_URL`, `CANTON_DEVNET_SYNCHRONIZER`, `CANTON_DEVNET_TOKEN` | blank, `dev-synchronizer`, blank | Same for devnet | Not for production |
| `SOLANA_TRANSFER_HOOK_PROGRAM_ID`, `SOLANA_CONFIDENTIAL_AUDITOR_ELGAMAL_PUBKEY` | blank | The SPL Token-2022 bond and confidential presets refuse to deploy until both are set | Set to use those presets |

### Contract addresses per chain

After a Foundry deployment, each contract address is configured per chain through a variable of the form `<CONTRACT>_<CHAIN>_<NETWORK>`, blank by default: for example `ASSET_TOKEN_FACTORY_ETH_MAINNET`. The families are `ASSET_TOKEN_FACTORY`, `TREX_FACTORY`, `ID_FACTORY`, `CLAIM_ISSUER`, `ORG_REGISTRY`, `PERMISSION_REGISTRY`, `PERMISSION_ORACLE`, `DAPP_REGISTRY`, `ECOSYSTEM_TIR`, `PAYMASTER` and, for Ethereum and Base only, the confidential `CONFIDENTIAL_FACTORY`, `CONFIDENTIAL_IDENTITY_REGISTRY`, `CONFIDENTIAL_COMPLIANCE`, `CONFIDENTIAL_OPERATOR_VIEWER` and `CONFIDENTIAL_AUDITOR_VIEWER`. `<CHAIN>` is `ETH`, `POLYGON`, `BASE`, `ARBITRUM`, `AVALANCHE` or `OPTIMISM`; `<NETWORK>` is `MAINNET` or `TESTNET`. `REPO_MARKET_FACTORY_ETH_MAINNET` and `_ETH_TESTNET` configure the lending market factory. Not every family exists for every chain; the exact set is the `registerwerk.contracts` block of `application.yml`. A feature whose contract is not configured is unavailable on that chain.

## Signing custody and wallets

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_WALLET_STORAGE_BACKEND` | `FILESYSTEM` | `FILESYSTEM` or `POSTGRES` keystore storage | `POSTGRES` with more than one replica |
| `REGISTERWERK_WALLET_DIR` | `/data/wallets` | Keystore directory (`FILESYSTEM` only) | Persistent volume |
| `REGISTERWERK_WALLET_RETENTION_DAYS` | `90` | Soft-deleted wallets keep their encrypted key material this long before it is destroyed | Review |
| `REGISTERWERK_WALLET_KMS_KEY_ID` | blank | The KEK key (not the signer) when `REGISTERWERK_WALLET_KEK_PROVIDER` is `GCP_KMS`, `AWS_KMS` or `AZURE_KEY_VAULT` | Required with a KEK provider |
| `KEK_REWRAP_ENABLED` | `true` | Nightly re-wrap of every envelope-encrypted secret (wallet keys, TOTP, webhook, Travel Rule) onto the active KEK version after a rotation; see [KEK rotation](../security/kek-rotation.md) | Leave on; `false` means re-wrapping by hand via `POST /api/v1/admin/kek/rewrap` |
| `KEK_REWRAP_CRON` | `0 30 3 * * *` | Schedule of that job (Spring cron, one node via ShedLock) | |
| `KEK_REWRAP_BATCH_SIZE` | `100` | Rows read per page; each row is its own transaction | |
| `REGISTERWERK_WALLET_KMS_PROVIDER`, `_KEY_VERSION`, `_TIMEOUT`, `_MAX_ATTEMPTS`, `_RETRY_BACKOFF`, `_HEALTH_CHECK_TIMEOUT` | `gcp`, blank, `5s`, `3`, `200ms`, `10s` | Cloud-KMS signer, see [Cloud-KMS signer setup](../security/kms-signer.md) | Refused if malformed or unreachable while `REGISTERWERK_WALLET_SIGNER=kms` |

## Sign-in and access

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_AUTH_LOGIN_LOCKOUT_MINUTES` | `15` | Base lock for an (e-mail, source address) pair after repeated failures | Keep |
| `REGISTERWERK_AUTH_LOGIN_MAX_LOCKOUT_MINUTES` | `240` | The lock doubles per episode up to this cap | Keep |
| `REGISTERWERK_AUTH_LOGIN_IP_MAX_FAILURES` | `30` | Failures from one address in the window before that address is refused (password spray) | Keep |
| `REGISTERWERK_AUTH_LOGIN_GLOBAL_MAX_FAILURES` | `600` | Platform-wide failures per minute above which addresses that already failed are refused | Keep |
| `REGISTERWERK_AUTH_LOGIN_MAX_TRACKED_KEYS` | `200000` | Bound on `login_attempt` rows; new account rows stop above it | Keep |
| `OPERATOR_EMAIL_DOMAINS` | blank | Allow-list of e-mail domains for invited operator accounts (comma separated); blank is off | Recommended |
| `ACCESS_REVIEW_SOD_CONFLICTS` | `REGISTRY_ADMIN+COMPLIANCE_OFFICER` | Role pairs that should not be held together; shown per access-review item as a warning only, not enforced at role assignment | Review |
| `ENTRA_AUTHORIZATION_URI`, `ENTRA_GRAPH_BASE_URL`, `ENTRA_AUTHORITY_BASE_URL`, `ENTRA_MFA_SETUP_URL` | Microsoft public-cloud URLs | Change only for a sovereign or national cloud | Review |
| `ENTRA_REQUIRE_2FA_ENROLMENT` | `false` | Require Entra users to have a second factor enrolled | Review |

## Sperrvermerk, vaults and lending

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `SPERRVERMERK_FREEZE_SWEEP_MS` | `300000` | The sweep that reads the outcome of submitted on-chain freezes and retries failed ones (backoff, 5 attempts) | Keep |
| `SPERRVERMERK_FREEZE_RECONCILE_CRON` | `0 30 2 * * *` | Nightly reconcile that re-sends missing freezes and compares every still-blocking block with the chain | Keep |
| `VAULT_DEALING_CUTOFF_UTC`, `VAULT_DEALING_PERIOD_SECONDS` | `17:00`, `86400` | Dealing cut-off sent to new ERC-7540 vaults, see [Forward pricing](../blockchain/forward-pricing.md) | Subscriptions refused on a vault without a cut-off |
| `REGISTERWERK_LENDING_REQUIRE_FACTORY` | `true` | Market registration requires the configured factory to vouch for the address | Tests only; keep `true` |
| `REGISTERWERK_LENDING_LOCAL_DEMO_ADDRESSES_FILE`, `LENDING_LOCAL_DEMO_BACKEND_RPC_URL`, `LENDING_LOCAL_DEMO_PUBLIC_RPC_URL` | blank, `http://anvil:8545`, `http://localhost:48545` | Local lending demo wiring | Leave blank |
| `REGISTERWERK_REPO_DESK_MIN_MARGIN_CURE_HOURS`, `REGISTERWERK_REPO_DESK_DEFAULT_GRACE_HOURS` | `24`, `24` | Minimum margin cure period and default grace after notice (interim values, T5-09) | Review |
| `REGISTERWERK_PAYMASTER_VOUCHER_VALIDITY_SECONDS`, `_MAX_FEE_PER_GAS_CAP_WEI`, `_MAX_TOTAL_GAS`, `_ENTITY_MONTHLY_CAP_SHARE` | `300`, `50000000000`, `3000000`, `0.10` | Gas-sponsorship limits; the last is the share of a policy's monthly cap one legal entity may use | Review |

## Trading

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_TRADING_ENABLED` | `true` | Secondary-market module | Review |
| `REGISTERWERK_TRADING_OFFCHAIN_SETTLEMENT_ON_DEPLOYED_ASSETS` | `false` | Allows listing and settling chain-deployed assets on the simulated venue without an on-chain leg; the holder sync would reset the seller to its on-chain balance | Keep `false` |
| `REGISTERWERK_TRADING_DEMO_INSTANT_SETTLEMENT` | `false` | Demo only: moves the register inside the buyer's request with no cash leg | Refused if `true` |
| `REGISTERWERK_TRADING_MAX_OPEN_RESERVATIONS_PER_BUYER` | `3` | Open reservations per buyer | Review (T5-04) |
| `REGISTERWERK_TRADING_RESERVATION_COOLDOWN_HOURS` | `24` | Cool-down after a reservation ends | Review (T5-04) |
| `REGISTERWERK_TRADING_UNRESOLVED_ALERT_HOURS` | `24` | `PAYMENT_UNRESOLVED` trades older than this raise the aged-unresolved gauge and an hourly alert log | Keep |
| `REGISTERWERK_TRADING_ALLOW_RELATED_PARTY_TRADES` | `false` | Related parties (shared UBO, member or wallet) may not trade unless `true` | Keep `false` |
| `REGISTERWERK_TRADING_MAX_PRICE_DEVIATION_BPS` | `0` | Price collar against the last unrelated trade, in basis points; `0` is off | Review |
| `REGISTERWERK_TRADING_FIAT_CURRENCIES` | `EUR` | ISO 4217 currencies accepted for SEPA, cross-border and Pontes listings | Review |
| `REGISTERWERK_TRADING_VENUE_SIMULATED_ENABLED` | `true` | The simulated venue | Demo only |
| `REGISTERWERK_TRADING_VENUE_{ASSETERA,ARCHAX,TALOS}_ENABLED`, `_BASE_URL`, `_API_KEY` | enabled `true`, blank, blank | External venue adapters | Configure only the ones you contract with |
| `REGISTERWERK_TRADING_VENUE_CLASSIFICATION`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF` | `DEMO_ONLY`, blank | Venue classification; a non-demo value needs a legal-opinion reference | Refused otherwise; see [release gates](../security/production-mode.md) |

## Travel Rule

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_TRAVEL_RULE_PROTOCOL` | `NOOP` | `NOOP`, `TRP` or `NOTABENE` | Refused unless `TRP` or `NOTABENE` |
| `REGISTERWERK_TRAVEL_RULE_OWN_VASP_DID`, `_OWN_VASP_LEI`, `_OWN_VASP_LEGAL_NAME` | blank | The operator's own VASP identity in outbound messages | Refused if unset |
| `REGISTERWERK_TRAVEL_RULE_INBOX_API_KEY`, `_LEGACY_SHARED_KEY` | blank, `false` | Deprecated shared key for the inbound inbox; per-peer HMAC credentials replace it. Blank disables the inbox | The legacy key is refused |
| `REGISTERWERK_TRAVEL_RULE_SEND_TIMEOUT_SECONDS` | `15` | A gated operation waits at most this long for the protocol to accept the message, then is refused | Keep |
| `REGISTERWERK_TRAVEL_RULE_REGISTER_INTERNAL_EXEMPT` | `false` | Exempts register-internal transfers; a legal position (T6-06) | Keep `false` until signed off |
| `REGISTERWERK_TRAVEL_RULE_WALLET_PROOF_VALIDITY_DAYS` | `365` | Validity of a wallet-control proof | Review |
| `REGISTERWERK_TRAVEL_RULE_SELF_HOSTED_VERIFICATION_THRESHOLD_EUR` | `1000` | Ownership-verification threshold for self-hosted addresses (Art. 14(5) TFR). It is not a messaging threshold | Review |
| `REGISTERWERK_TRAVEL_RULE_MICA_ENFORCEMENT_DATE` | `2026-07-01` | End of the MiCA transitional period; see [CASP register import](../../compliance/casp-register-import.md) | Keep |
| `REGISTERWERK_TRAVEL_RULE_NOTABENE_BASE_URL`, `_API_KEY`, `_VASP_DID` | `https://api.notabene.id`, blank, blank | Notabene adapter | Key and DID required for `NOTABENE` |
| `REGISTERWERK_TRAVEL_RULE_TRP_ENDPOINT`, `_TRP_MTLS_CERT`, `_TRP_MTLS_KEY`, `_TRP_DIRECTORY_URL` | blank, blank, blank, `https://trp.notabene.id/directory` | TRP adapter | Endpoint and mTLS material required for `TRP` |

## Regulatory reporting

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_OPERATOR_TIN`, `REGISTERWERK_OPERATOR_LEI` | blank | Operator identifiers used in the draft reports | Set |
| `REGISTERWERK_REPORTING_GATEWAY` | `NOOP` | Report transport; `NOOP` sends nothing | Review |
| `REGISTERWERK_REPORTING_SFTP_{DE,FR}_HOST`, `_PORT`, `_USER`, `_KEY`, `_DIR` | blank, `22`, blank, blank, `/incoming/mifir/` | SFTP drop per country. It proves transport, not filing or acceptance by an authority | Review |
| `REGISTERWERK_REPORTING_PROTOTYPE_ENABLED` | `false` | The MiFIR/DAC8 prototype (outputs are `DRAFT_UNVALIDATED`) | Refused if `true` |

## Audit log

| Variable | Default | Purpose | Production |
|---|---|---|---|
| `REGISTERWERK_AUDIT_LEGACY_LISTENER` | `true` | Drains event publications created before the capture listener existed; switch off once `event_publication` has no incomplete audit rows | Review |
| `REGISTERWERK_AUDIT_SIGNING_PROVIDER` | blank | Signing key provider for the audit chain: blank (no signature), `ENV_VAR` (development and test only) or `GCP_KMS`. AWS KMS and Azure Key Vault are not offered because neither supports Ed25519 signing keys | Refused when blank; `GCP_KMS` for real signing (see [release gates](../security/production-mode.md)) |
| `REGISTERWERK_AUDIT_SIGNING_SEED` | blank | Ed25519 seed for the `ENV_VAR` provider | Development only |
| `REGISTERWERK_AUDIT_SIGNING_GCP_KMS_KEY_VERSION` | blank | Ed25519 key version for the `GCP_KMS` provider | Required with `GCP_KMS` |
| `REGISTERWERK_AUDIT_ANCHOR_SINK` | `none` | `s3` publishes the daily anchor to an Object Lock bucket | Recommended `s3` |
| `REGISTERWERK_AUDIT_ANCHOR_S3_BUCKET`, `_PREFIX`, `_ENDPOINT`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY`, `_PATH_STYLE` | blank, `audit-anchors/`, blank, `eu-central-1`, blank, blank, `false` | The bucket (created with Object Lock) and its credentials; blank keys use the default AWS credential chain (IRSA, workload identity); an endpoint selects an S3-compatible store, path-style addressing for MinIO | Bucket required with `s3` |
| `REGISTERWERK_AUDIT_ANCHOR_S3_RETENTION_MODE`, `_RETENTION_DAYS`, `_CREATE_BUCKET` | `COMPLIANCE`, `3650`, `false` | Object Lock mode and retention of each anchor object; `_CREATE_BUCKET` is a local-demo convenience; provision production buckets out of band | Keep `COMPLIANCE`; leave `_CREATE_BUCKET` `false` |

## Telemetry

| Variable | Default | Purpose |
|---|---|---|
| `OTEL_SAMPLING_PROBABILITY` | `0.1` | Share of requests traced |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://tempo:4318/v1/traces` | OTLP/HTTP trace endpoint |

## Settings without an environment variable

A few settings can only be changed as properties (for example through `SPRING_APPLICATION_JSON`, or the relaxed environment name): the [approval queue limits](../security/approval-queue.md#limits), the dual-control window `registerwerk.auth.step-up.dual-control.window-seconds` (300), the screening controls under `registerwerk.screening.*`, the [technical retention windows](../../compliance/retention.md#technical-artefacts-purged-automatically) under `registerwerk.retention.*`, and `registerwerk.kyc.max-validity-months` (12).
