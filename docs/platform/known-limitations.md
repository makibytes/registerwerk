---
title: Open decisions and known limitations
description: What the platform does not do, what has not been verified, and the product and legal decisions that are still open together with the interim behaviour built into the code.
---

# Open decisions and known limitations

This page lists, in one place, what you should not assume. It has three parts: [known limitations](#known-limitations) (features that are not built, and checks that were not made), [decisions that have been taken](#decisions-taken) and [open decisions](#open-decisions), each with the conservative interim behaviour in the code and where to change it.

!!! warning "Reading this page"
    The repository is a reference implementation. Nothing here, and nothing in the product, establishes eWpG compliance, regulatory authorisation, legal effect or production readiness. An open decision means the code contains a deliberately cautious interim that a responsible person at the operator must confirm or replace before relying on it.

## Known limitations

### Features that are not built

| Area | Limitation |
|---|---|
| Stellar | The first stage only: the asset id is recorded with a signed `ManageData` transaction, and clawback and trustline authorisation controls exist. Creating holder trustlines and distributing an initial balance (the second stage) is not built, the indexer sees only payments that touch the issuer account (not holder-to-holder transfers), and there is no automated chain-confirmation path ([Starknet and Stellar](../blockchains/starknet-stellar.md)) |
| Solana | Token-account indexing is not implemented. The indexer lists signatures by mint address, so plain SPL transfers are not seen; Solana deployments are reported as partially observed and the holder sync stays blocked ([Solana indexer](../operator/indexers/solana.md)) |
| Freezes on other chains | The on-chain freeze for a Sperrvermerk reaches EVM tokens (including ERC-3525, vault shares and confidential ERC-3643). Starknet, Solana and Stellar freezes are reported as **unsupported** and need a manual operator action; there is no automation, because those calls are not sent through the durable outbox |
| Burns on redemption | Burns for ERC-3643, Solana, Starknet and Stellar assets remain manual; the redemption then reports the incomplete burn as a follow-up |
| Register units on some tokens | Starknet ERC-20 (fixed 18 decimals), Stellar assets (7 decimals), vault shares and confidential tokens are refused by the register-unit guard instead of being deployable with 0 decimals. Existing deployments keep their recorded decimals and are refused until the asset is redeployed |
| Travel Rule peers | Peer authentication is a per-peer HMAC credential. mTLS between Travel Rule peers is not implemented (the TRP adapter has its own mTLS material for the protocol endpoint) |
| Data-subject requests | The export covers legal-entity master data. Aggregation for **natural persons** (beneficial owners, signatories, users) is not built; an erasure ends as `COMPLETED_PARTIAL` |
| Retention | Nothing compliance-relevant is deleted at the end of its retention period; there are no sweeps for KYC, screening, register, audit or reporting records. Only technical artefacts are swept ([Retention matrix](../compliance/retention.md)) |
| Impersonation | The customer is not notified when an operator views their data in a read-only support session (the customer can list sessions under `GET /api/v1/company/impersonation-sessions`, but nothing is pushed) |
| Approvals | The approval queue does not notify approvers (no e-mail); a dialog does not re-attach to an open request after a page reload |
| Languages | The operator and customer portals are English only. The documentation exists in German, French, Spanish and Italian for part of the pages; new pages fall back to English with a banner |
| Reinstatement of customers | Reinstating a closed or dissolved customer has no Sperrvermerk check at completion (the party gates enforce blocks on each transaction), and a reinstated merge source keeps its merge record |
| Held entitlements | A coupon or redemption entitlement held back for a blocked holder has no automated "pay later" path; an operator resolves it outside the system |
| Operator UI gaps | The replacement of a legacy ERC-3643 compliance module is API only; there is no read view of per-deployment freeze status ("confirmed on N of M deployments") |
| Migration pod | Flyway runs inside the backend, so the backend pod also holds the schema owner's password. A separate migration job is not built |

### Where the product is looser than the backend

- **The lending and repo contracts are permissionless.** The backend gates for lenders (professional or eligible-counterparty classification, approved KYC, no screening hit) and borrowers protect the product surface only. Anyone can call the contracts directly with their own wallet, and the backend cannot stop it. Likewise, investor-signed vault requests (`requestDeposit`, `requestRedeem`) cannot be refused by the backend.
- **Forward pricing proves the order of events, not the price.** The contract checks that the NAV was struck after the dealing point; the NAV itself remains an operator input.
- **Dual control is two operators, not two organisations.** Whether that is adequate is a policy question. A raw private key or keystore password supplied to an import or export is not part of the approved request body, so the initiator can change it after approval (only the method, path and query stay bound).

### What has not been verified

| Item | State |
|---|---|
| Cloud-KMS signer | Tested against a mocked KMS client only; never run against a real Cloud KMS project ([setup and verification steps](../operator/security/kms-signer.md)) |
| WAL archiving | The local file archive was drilled (point-in-time recovery with a recorded resilience test). S3 mode, CloudNativePG and managed-PITR paths are documented but not exercised. The restore time was measured on a near-empty database, so **no RTO is promised**; the metric names for CloudNativePG should be checked against the version you run |
| Chain behaviour | Freeze, unfreeze, compliance-module replacement and multi-node second-source confirmation were tested with mocked ports, not on a real multi-node chain |
| Reinstatement tests | The reinstatement tests (customer state machine and migration) were written together with the code, so there is no red run against the unchanged code; a re-injection check is still owed |
| Immediate-path idempotency | Idempotency of the immediate EVM send path is not de-duplicated at the nonce level (a design change to the nonce hot path); the `Idempotency-Key` is honoured at the API and on the outbox row |
| Audit signatures | Audit signatures do not yet carry a domain-separation prefix |
| Block-time evidence | Record-date freshness uses on-chain block time for EVM (Graph Node) only; Starknet, Solana, Stellar and Canton fall back to the wall-clock rule |
| Browser verification | The headless-browser smoke checks are run as a separate release step against the built containers and are not part of this documentation |

### Operations

- The compose and demo stack uses a Postgres superuser as schema owner. Production needs a non-superuser owner and the DML-only runtime login ([Production mode](../operator/security/production-mode.md)).
- An existing Helm release must add `runtime-password` to `registerwerk-postgres-secret` before upgrading, or the init container fails fast.
- An S3 audit-anchor bucket with the default `COMPLIANCE` retention of 3,650 days keeps its objects for ten years even for a try-out; use `GOVERNANCE` and a short retention for trials.
- In production mode, a registry-mutating transaction on a chain with fewer than two healthy RPC nodes stays pending instead of completing. A permanently dead configured node blocks automatic nonce repair until it is disabled.
- The two portals must be served on distinct hostnames in production (the session cookie is shared per host). One-host deployments need the documented workaround.

## Decisions taken

These were decided during the remediation programme and are implemented.

| Id | Decision | Where it shows |
|---|---|---|
| T8-02 | Build an in-app approval queue for four-eyes actions | [Approval queue](../operator/security/approval-queue.md) |
| T8-03 | `COMPLIANCE_OFFICER` takes KYC, EDD and beneficial-owner decisions in the operator portal through a scoped entity read and a KYC queue page; EDD stays `REGISTRY_ADMIN` plus a second approver | Operator portal KYC queue (not yet described in the [KYC process](../operator/customers/kyc-process.md) guide) |
| T6-05 | Demo keeps act-on-behalf; production allows read-only impersonation only, for `REGISTRY_ADMIN` and the new `SUPPORT_AGENT`; no customer notice | [Impersonation](../operator/customers/impersonation.md) |
| T2-20, T5-13 | Lenders need KYC and professional or eligible-counterparty classification in production mode (the same gates as borrowers) | [Production mode](../operator/security/production-mode.md) |
| T1-07 | Forward pricing with a dealing cut-off for ERC-7540 vaults | [Forward pricing](../operator/blockchain/forward-pricing.md) |
| T3-03 | No tax-flavoured wording: the document is an income statement, "not a tax certificate", and Registerwerk withholds and computes no tax | Customer positions page, redemption docs |
| T8-07 | English-only portals for this release | Limitation above |
| T6-12 | A closed or dissolved customer can be reinstated through four eyes and a legal reference, never straight to active; re-screening and KYC are mandatory | [Offboarding](../operator/customers/offboarding.md#reinstating-a-closed-or-dissolved-customer) |
| T7-04 | WAL archiving, a real restore drill and its recorded result | [Backups](../operator/maintenance/backups.md), [DR runbook](../operator/dr/runbook.md) |
| T7-05 | Cloud-KMS signer, GCP first (an assumption: AWS KMS can follow behind the same interface); SoftHSM stays demo only | [Cloud-KMS signer setup](../operator/security/kms-signer.md) |
| T6-17 | Separate migration and runtime database logins, and an S3 Object Lock audit anchor | [Audit log](audit-log.md) |
| T6-10 | Nothing is purged automatically; a retention matrix documents period, basis, owner and placeholder | [Retention matrix](../compliance/retention.md) |
| T7-03, T6-01, T6-02, T6-11, T5-06 | Keep the implemented interim; thresholds stay configuration keys to be replaced by the operator's own GwG §5 risk analysis | Rows below |

## Open decisions

Everything below keeps its conservative interim until the responsible person decides. "Change at" names the setting or place; "policy" means the answer is an operator or legal decision that is then implemented by a code change or a procedure.

### Funds, bonds and vaults

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T1-11 | Floating-rate fixing conventions (lag, reset per period) | Floating coupons are blocked from automatic settlement | Corporate-action settlement (code) |
| T1-04 | Vault liquidity model and redemption gate | No gate by default (the recommended option) | Vault configuration |
| T1-05 | Where a frozen investor's escrowed shares and cash go | Held in place; force-cancel only under a legal order | Operator action with step-up and four eyes |
| T1-06 | Third-party payer or non-owner receiver in vaults | Payer whitelisted; receiver is the owner or whitelisted | Vault contract |
| T1-18 | Liquidation grace after unpause | Operator-settable `liquidationGraceUntil` | Lending market administration |

### Access, sponsorship and look-through

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T2-01, T2-02 | Who may use a gas-sponsorship policy and who owns unused budget | Holders of the asset only may use a policy; no interim is recorded for unused budget | Sponsorship policy settings |
| T2-17 | Custody model for passkey-account guardians (guardian execution is a full custodial override) | No interim beyond the implementation; treat guardian execution as custodial | Policy |
| T2-18 | Look-through of nominee-pool (repo, DvP) coupon and redemption entitlements | Held (`HELD_LOOK_THROUGH`) until decided | Policy, then code |
| T2-19 | Response to an unreviewed sanctions hit on-chain | The hit event is published but nothing consumes it | Policy, then code |

### Registrar, tax and corporate actions

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T3-07 | Corporate actions with record date before and payment date after a register handover; identity fields in the §20 package | Clear names and addresses omitted from the package | Policy |
| T3-08 | Collective assets (custodian as register holder?); payment basis and over/under-payment policy | As implemented | Policy |
| T3-10 | Which SchVG amendments are allowed after issuance | Locked except an operator four-eyes amendment with a legal reference | Terms amendment (code) |
| T3-11 | §10 inspection verification model | Only a verified issuer or holder is auto-approved | Policy |
| T3-13 | Who may instruct a §17(2) register change | Operator only, against a recorded instruction | Policy |
| T3-14 | Retention channel for §19 statements after erasure | Fails with `NO_LAWFUL_CHANNEL`; a retained-channel note is required | Policy |
| T3-17 | Successor registrar's custody wallet on the same contract | Register the destination wallet before completing | Procedure |
| T3-20 | ERC-3525 register entry per holder or per holder and slot | Sum across slots | Policy |
| T3-21 | Should holders ever be eligible for `ASSET_TOKEN_ADMIN` | No | Policy |
| T3-22, T5-10 | Manufactured income payments for repo collateral | Redemption blocked while a repo is open; informational events | Policy |

### Chain infrastructure

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T4-01 | Unit convention for register amounts | Whole units: register tokens deploy with 0 decimals (the recommendation, option b), confirm | [Register units](register-units.md) |
| T4-02 | Fee ceilings per chain, deferral and daily gas budget | Global caps (500 gwei max fee, 50 gwei tip) with per-chain override; the operation fails with an alert | Chain configuration |
| T4-03 | May an operator cancel or replace a stuck regulatory operation | Never automatic; alert plus operator cancel or re-price with step-up and four eyes | Outbox administration |
| T4-04 | May the registry issue or force-transfer to a non-onboarded address | No; the destination must be an active, KYC-approved, screened holder | `DESTINATION_GATE_ENABLED` is demo only; otherwise policy |
| T4-05 | Manual compliance claim for a non-approved entity | Refused in production | Policy (`CLAIMS_ALLOW_UNAPPROVED_IN_NONPROD` is non-production only) |
| T4-06 | Which actions need a case reference; authoritative legal-order register | Approver id persisted everywhere; `legalBasis` mandatory on forced operations | Policy |
| T4-07 | Production custody model, break-glass role, signer rotation workflow | KMS or HSM; software wallets use KEK-wrapped keys; rotation is a manual procedure | [KEK rotation](../operator/security/kek-rotation.md) |
| T4-08 | Finality level at which an eWpG entry is effective; may chaincache finality count | Finality parameters from `chain_config` only; second-source receipt check | Chain configuration |

### Markets, cash leg and financing

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T5-01 | Settlement model for secondary trades | Seller-confirmed; instant settlement demo only | `REGISTERWERK_TRADING_DEMO_INSTANT_SETTLEMENT` (refused in production) |
| T5-02 | Arbitration owner, SLA and evidence for an unconfirmed off-chain payment | `PAYMENT_UNRESOLVED` and `DISPUTED` states with an operator queue | Operator procedure |
| T5-03 | Settlement currencies and price convention | Per unit; currency mandatory on new listings | `REGISTERWERK_TRADING_FIAT_CURRENCIES` |
| T5-04 | Reservation economics and surveillance scope | 3 open reservations, 24 h cool-down, related parties blocked, collar off | `REGISTERWERK_TRADING_MAX_OPEN_RESERVATIONS_PER_BUYER`, `_RESERVATION_COOLDOWN_HOURS`, `_ALLOW_RELATED_PARTY_TRADES`, `_MAX_PRICE_DEVIATION_BPS` |
| T5-05 | Minimum tradable unit and denomination | Whole-denomination multiples for bonds with a denomination | Bond terms |
| T5-06 (kept) | Is the operator running an MTF, OTF or SI through peer listings | Demo only; bilateral listings as production shape; a legal-opinion reference is required to change | `REGISTERWERK_TRADING_VENUE_CLASSIFICATION`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF` |
| T5-07 | Who may be a repo participant | Professional or eligible counterparty; opt-in directory | Policy |
| T5-08 | Register representation of repo collateral | Internal encumbrance quantity; no register write | Policy |
| T5-09 | GMRA default mechanics | Evidence plus notice, cure and grace only; no close-out calculation | `REGISTERWERK_REPO_DESK_MIN_MARGIN_CURE_HOURS`, `REGISTERWERK_REPO_DESK_DEFAULT_GRACE_HOURS` |
| T5-11 | SFTR reporting responsibility | Parties report; the operator provides the fields | Policy |
| T5-12 | Who attributes a forced move of pledged collateral and how fast | Balance guard pauses the market; reconcile with step-up and a second approver | Operator procedure |
| T5-14 | Customer-facing KYC rejection categories | Fixed four-value enum; internal reason only in the audit and operator UI | Code |

### Compliance and customers

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T6-01 (kept) | Minimum due-diligence record set and validity | Jurisdiction checklist, 75 % identified ownership or a documented fallback, validity capped at 12 months | `registerwerk.kyc.max-validity-months`; replace with your GwG §5 analysis |
| T6-02 (kept) | Risk methodology, PEP and EDD process | Separate PEP resolution, EDD approval with a second approver, 6-month cap, no rating engine | Policy |
| T6-03 | Validity of an accepted false positive; reliance on a stale CLEAR | 90 days; 24 h grace after the first failed run, never older than 72 h | `registerwerk.screening.accept-validity-days`, `.stale-clear-grace-hours`, `.stale-clear-max-age-hours` |
| T6-04 | Screening provider, thresholds, related-party scope | OpenSanctions, threshold recorded per run | `registerwerk.screening.match-threshold`; a licensed provider before production |
| T6-06 | Does the Travel Rule apply to eWpG register transfers; are internal and court-ordered transfers exempt | Wallet-control proof, gate on the ERC-3643 path, no exemption | `REGISTERWERK_TRAVEL_RULE_REGISTER_INTERNAL_EXEMPT` |
| T6-07 | Counterparty VASPs absent from the MiCA register | Fail closed; third-country allow-list with reviewer and expiry | [CASP register import](../compliance/casp-register-import.md) |
| T6-08 | Who is the receiving CASP of record for registered holder wallets | Operator records and flags; no hold | Policy |
| T6-09 | STR/SAR workflow and channel | No STR feature is claimed; hits stay blocking | External case system |
| T6-10 | Retention periods, controller roles, natural-person DSAR | Planning periods; nothing purged; export is master data only | [Retention matrix](../compliance/retention.md) |
| T6-11 (kept) | May a legal-block type auto-expire | None by default; expiry opens a review task and the block keeps applying | `SPERRVERMERK_AUTO_EXPIRE_TYPES` |
| T6-13 | Closing a customer that is obligor of outstanding securities | Refused unless the operator acknowledges each obligation with a reason | Procedure |
| T6-14 | Which entity changes force re-KYC | Re-screening and a review task; step-up for less protective classification and merges | Code |
| T6-15 | Prohibited role pairs and recertification cadence | Warning only; write-once decisions | `ACCESS_REVIEW_SOD_CONFLICTS` |
| T6-16 | Who classifies DORA incidents; may detectors open them | Manual classification endpoint; deadlines from awareness time | Procedure |
| T6-18 | May an issuer replace the term sheet after issuance | Only through an operator-approved amendment | Terms amendment |
| T6-19 | Wallet-address sanctions screening vendor and proof strength | Bind-challenge signature; settlement wallet must already be bound | Policy |

### Platform and frontends

| Id | Question | Interim in the code | Change at |
|---|---|---|---|
| T7-01 | May register PDFs be issued unsigned in production | No: production refuses start-up without a signing keystore | `registerwerk.docsig.allow-unsigned` |
| T7-02 | Holder-to-holder entity name visibility | Off: issuers see their holders, holders see only their own counterparties | Code |
| T7-03 (kept) | Unrotated bootstrap password: outage or login restriction | Boot refusal only while the seeded account is the sole administrator; otherwise a login restriction and alert | [Production mode](../operator/security/production-mode.md) |
| T7-06 | DORA early-warning lead times and recipients | Gauge of seconds to the next deadline and a warning alert below one hour | `monitoring/alerts/registerwerk.yml` |
| T7-07 | Lifetime of the relayer's operator decrypt authorisation | Production requires an explicit duration (default 365 days in development) | Relayer `OPERATOR_DECRYPT_DURATION_DAYS` |
| T8-01 | Customer-facing portfolio value | Nominal totals per currency; indicative marks shown separately, never summed | Code |
| T8-04 | Distinct hostnames for the two portals | Documented requirement and workaround; no code change | Deployment |
| T8-05 | Retain or re-render the register-inspection PDF | One-shot server behaviour; the client keeps the download | Code |
| T8-06 | Wording for overdue, missed and defaulted coupons | Factual, date-based labels; "default" only when the operator has declared it | Code |
