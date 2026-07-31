---
title: Registerwerk assurance review ledger
description: The proposed control record for a future multidisciplinary review of Registerwerk — not evidence that any review has taken place.
---

# Registerwerk assurance review ledger

Last updated: 2026-07-29

> **No review described in this document has taken place.** No domain panel and no IT board
> has been convened, appointed, or consulted. Every entry below was written by an automated
> contributor as a *proposed* review structure and a self-assessment of the repository. Read it
> as a plan for a future review, never as evidence that one happened. A completed code change is
> not a legal certification. Items that depend on instrument terms, an operator licence, external
> evidence, deployment configuration, or qualified counsel remain undecided.

This document proposes the control record for a future multidisciplinary review of Registerwerk:
what would be reviewed, by whom, and what evidence each verdict would require.

## Proposed decision protocol

The following panels are proposed, not staffed. Domain panels would cover bond issuance and settlement, payments, financial crime and regulatory compliance, crypto assets and trading, audit, and repo/lending. An IT board would cover software design and implementation, architecture, SRE, frontend, and cryptography.

Under the proposal the IT board would score proposals from 0 to 2 on legal-invariant fidelity, ledger correctness, architecture, security/privacy, data lifecycle, UX/accessibility, operability, and verification. A proposal would be:

- approved at 14–16 with no zero in the first five dimensions;
- approved with changes at 9–13;
- dismissed at 0–8.

The board would be able to veto cross-tenant access, unsafe keys, floating-point money, non-idempotent settlement, irreversible migrations, weakened dual control, unbounded input, missing finality/reorg handling, unobservable reconciliation, or a legal invariant without acceptance criteria.

Statuses proposed for this ledger are `PENDING`, `BLOCKED_DECISION`, `APPROVED`, `IN_PROGRESS`, `VERIFIED`, `DISMISSED`, and `RESIDUAL_RISK`. None has been assigned by a reviewer.

## Review coverage

Every cell is `Not performed`. The scope column records what a review *would* cover. Automated
self-assessment is tracked separately in `docs/claims/registry.json`, where it carries the
`SELF_ASSESSED_UNREVIEWED` status.

| Phase | Pieces | Domain review | IT review | Implementation |
|---|---|---|---|---|
| Inventory | Spring backend and 31 domain modules; EVM, Cairo and DAML contracts; EVM/Solana/Canton/Starknet/Stellar indexers; operator and investor Angular apps plus shared UI; confidential-token relayer; Kong, Compose, Helm and monitoring; documentation | Not performed | Not performed | Baseline only |
| 0 — invariants | Actor/capacity model, instrument perimeter, register authority, asset and money units, finality, claims, release gates | Not performed | Not performed | Partial, self-assessed only |
| 1 — authority and compliance | Authentication, authorization, organizations, KYC/AML, screening, travel rule, jurisdiction approvals, audit, privacy, central operation gate | Pending | Pending | Pending |
| 2 — issuance and settlement | Asset lifecycle/deployment, token standards, identity/compliance contracts, registry, payments, DvP, custody, indexers, corporate actions | Pending | Pending | Pending |
| 3 — markets and reporting | Marketplace/trading, repo/lending, oracle and NAV, bond servicing, MiFIR, DAC8/KStTG, DORA and jurisdiction profiles | Pending | Pending | Pending |
| 4 — user interfaces | Operator UI, investor UI, shared UI, API contracts, accessibility and safe transaction presentation | Pending | Pending | Pending |
| 5 — operations | CI, dependencies, containers, Kong, Helm, secrets, network policies, monitoring, backup/restore, SLOs and runbooks | Pending | Pending | Pending |
| Closure | Full tests, replay/reconciliation evidence, claim reconciliation, migration notes and residual-risk sign-off | Pending | Pending | Pending |

## Phase 0 canonical model

### Authority and finality

Each instrument must have a versioned perimeter decision that names the legal register, the technical ledger, the projection direction, the register keeper, and the evidence required for legal effect. Token standard selection must not classify the instrument.

The system must not compress these dimensions into one `SETTLED` flag:

`INITIATED → EXECUTED → TECHNICALLY_FINAL → CASH_CONFIRMED → REGISTER_POSTED → RECONCILED → LEGALLY_EFFECTIVE`

For German eWpG instruments, the current database holder record is only the asserted legal register pending an instrument-specific, counsel-approved authority policy. A chain transaction alone must not be described as legal re-registration. Luxembourg, France, and Liechtenstein need their own instrument decisions rather than inheriting the German model. The basis for the German distinction is the current official [eWpG](https://www.gesetze-im-internet.de/ewpg/BJNR142310021.html); relevant product and operator decisions for France and Luxembourg must be checked against the [AMF DLT Pilot guidance](https://www.amf-france.org/en/news-publications/depth/pilot-regime) and the [Luxembourg dematerialised-securities framework](https://www.cssf.lu/en/Document/law-of-6-april-2013/).

### Unit conventions

| Value | Canonical convention |
|---|---|
| Registered quantity | Securities units with an explicit `quantityScale`; conversion to chain base units requires declared `tokenDecimals` |
| Currency | ISO-4217 major units in backend models plus explicit currency exponent and rounding |
| Bond face value | Major currency units per whole security unit |
| Issue price | Dimensionless fraction of face value; `1.00` means 100% |
| Fixed coupon | Annual decimal rate; coupon uses principal × annual rate × contractual day-count fraction, rounded per payee |
| Trade price | Major currency units per whole security unit with explicit currency |
| Token payment | Exact token base units after verified-decimal conversion |
| ERC-4626 NAV | WAD fixed point; `1e18` means one underlying base unit per share base unit |
| Repo price | Loan-token base units per whole zero-decimal collateral token |
| Repo rate/index | WAD; LTV, reserve factor and liquidation bonus use basis points |
| Time | Legal calendar/timezone for contractual dates; UTC instant and canonical block evidence for chain events |

### Claim baseline

| Claim | Finding | Required disposition |
|---|---|---|
| “Fully compliant” in DE/LU/FR/LI | False as an unconditional claim | Replace with scoped, evidenced, expiring decisions per instrument and operator |
| Every issuer/recipient passes KYC before value actions | False | Central server-side operation gate plus reviewed-document/BO/screening evidence |
| Database or blockchain is universally authoritative | Contradictory documentation | Select authority per instrument; distinguish legal register from technical ledger and projection |
| MiFIR reporting is production-ready | Placeholder | Quarantine output until population, RTS 22 schema, correction/deduplication and receipt handling exist |
| DAC8 export is ready | False/obsolete for current German implementation | Rebuild around reportable-user diligence, tax residence/TIN, flows, jurisdiction routing, corrections and KStTG decisions |
| MiCAR-compliant payment rails | False | Treat as operator attestations until issuer, classification, authorization and redemption evidence are verified |
| DORA incident automation | Placeholder | Keep manual records labelled as such; implement detection, classification, routing and submission evidence before claiming automation |
| PII is encrypted at rest | False for natural-person columns | Correct the claim or implement field/application encryption with key lifecycle and migration |
| All chains/standards are implemented | False | Starknet/Stellar and any other skeleton integration must be labelled placeholder |
| Same-chain DvP is atomic | Verified only for exact-transfer tokens and one transaction | Add exact-leg checks, finality/reorg evidence and legal-register reconciliation |

## Phase 0 proposal register (self-assessed, unreviewed)

| ID | Proposal | Self-assessment | Tracking state | Evidence recorded / blocker |
|---|---|---|---|---|
| M0-3525-A | Fix address-form ERC-3525 transfer so source decreases and destination increases exactly once | Proposed (unreviewed) | SELF_ASSESSED | Contract-conservation evidence only: regression tests plus full Foundry suite, 449 passed / 31 skipped; this does not prove indexed or legal-register reconciliation |
| M0-3525-B | Apply pause/freeze/whitelist policy to whole-token ownership transfers | Proposed, changes noted (unreviewed) | IN_PROGRESS | Enforce every guard through the ERC-721 ownership hook, preserve zero-address mint/burn semantics and forced-operation bypass, and test both transfer APIs plus atomic address-form failure |
| M0-3525-C | Enforce global and slot caps with explicit cumulative-vs-outstanding semantics | Blocked — decision required | BLOCKED_DECISION | Decide cumulative versus outstanding semantics, burn/redemption/forced-burn headroom, cap hierarchy, amendment and lowering behavior; reconcile legacy issuance/outstanding per slot |
| M0-7540-A | Disable inherited synchronous `deposit`, `mint`, `withdraw`, and `redeem`; advertise zero maxima | Proposed (unreviewed) | SELF_ASSESSED | All synchronous routes revert, maxima are zero, request tests pass; full Foundry suite exited 0 |
| M0-7540-B | Bind fulfillment to immutable, timely NAV strike metadata | Blocked — decision required | BLOCKED_DECISION | Decide forward/historic pricing, cutoff calendar/timezone, maximum age, eligible strike, correction/supersession and valuation authority; legacy requests remain `UNVERIFIED_STRIKE` |
| M0-4626 | Enforce NAV metadata/freshness and reserve-solvency model | Blocked — decision required | BLOCKED_DECISION | Decide cash-backed synchronous versus managed-portfolio async model, eligible reserves/custody, liquidity buffer, fees and redemption form |
| M0-REPO-A | Burn ceiling-rounded scaled shares on asset withdrawal and reject zero-share value movement | Proposed (unreviewed) | SELF_ASSESSED | Boundary test above 1e18 index plus repo invariants: 3 passed with 256 runs / 5,120 calls each |
| M0-REPO-B | Prevent remove/re-add from valuing one market more than once | Proposed (unreviewed) | SELF_ASSESSED | Re-add regression test and full Foundry suite pass; `marketCount` now remains unique |
| M0-REPO-C | Make supply, borrowing, repayment, liquidation and full exits share-conservative and overflow-safe | Proposed, changes noted (unreviewed) | PROPOSED | Use overflow-safe `mulDiv`, reject zero accounting units, record debt conservatively, base partial cash/collateral movement on actual debt deltas and make full exits explicit; immutable live markets still require inventory/unwind/replacement evidence |
| M0-REPO-RISK | Oracle cadence/override, LLTV/bonus relation, close factor and bad-debt waterfall | Blocked — decision required | BLOCKED_DECISION | Decide oracle/cadence/override quorum, LLTV/bonus relation, close factor/stale rule, loss waterfall and collateral legal/custody terms; do not change these in the arithmetic batch |
| M0-DVP | Exact-transfer leg verification, term-bound trade IDs and backend finality states | Proposed, changes noted (unreviewed) | PROPOSED | Technical batch only: both-account balance deltas, domain-separated term/salt ID and provisional event/receipt lifecycle; cancellation rights, chain finality threshold and legal settlement route remain product decisions |
| M0-BOND | Normalize decimals, maturity, record-date entitlements and quantity-based redemption | Blocked — decision required | BLOCKED_DECISION | Decide day count, business calendar/timezone, record/ex-date authority, rounding, withholding/suspense, default/call/amendment and partial-redemption terms; quarantine the current desk as reference-only |
| M0-LEDGER | Make settlement transitions monotonic, restore inventory exactly once and require independent cash/delivery evidence | Proposed, changes noted (unreviewed) | PROPOSED | Additive state/transition/evidence/reservation model; legacy `SETTLED` becomes unverified, buyer references cannot promote state, and `LEGALLY_EFFECTIVE` remains unavailable without a configured authority policy |
| M0-INDEXER-A | Repair configured handler-signature parity, factory deployment events and per-component address rendering | Proposed (unreviewed) | SELF_ASSESSED | Limited technical result: 16 contract ABIs / 71 configured handlers, address renderer, codegen, WASM build and validation-only wrapper pass; it does not prove deployed code identity |
| M0-INDEXER-B | Add provisional/final cursors, reorg rollback and direct-chain reconciliation | Proposed, changes noted (unreviewed) | PROPOSED | Build fail-closed provisional/orphan/rewind and checkpoint reconciliation plumbing; no event becomes `FINAL` until a separately approved chain policy and trusted RPC configuration exist |
| M0-INDEXER-C | Track ERC-3525 value by token/owner/slot, durable ERC-7540 request lifecycle including cancellation, and repo scaled/vault cash-flow state | Proposed, changes noted (unreviewed) | SELF_ASSESSED | All 25 entities have an enum projection status; first-observed incomplete histories stay `INCOMPLETE`; RepoVault is signed net asset cash flow, not principal; full static gate passes. No replay/finality proof exists |
| M0-INDEXER-D1 | Support every configured BondDesk/AMM/RepoVault instance, update operator migration docs, and make the test gate compile mappings | Proposed, changes noted (unreviewed) | SELF_ASSESSED | All instances are explicit; `NONE` is an operator assertion; live deploy requires a fresh label; graph-node reload precedes deploy; per-source blocks and non-destructive rollback are documented and cross-reviewed |
| M0-INDEXER-D2 | Verify RPC bytecode and approved runtime code hash/component identity before deployment | Blocked — decision required | BLOCKED_DECISION | Requires authoritative per-chain inventory, approved artifacts/runtime/proxy/admin hashes, key expectations and rotation policy; syntactic address checks are not identity verification |
| F0-001 | Versioned instrument perimeter, legal capacities, regulatory authorizations and ledger authority policy | Blocked — decision required | BLOCKED_DECISION | Counsel/operator decisions per jurisdiction and instrument; F0-002 may add a schema shell but must seed no active blanket allow |
| F0-002 | Central `AssetOperationGate` enforced in services and HTTP paths | Proposed, changes noted (unreviewed) | PROPOSED | Versioned, scoped, expiring/revocable service-layer decision snapshots; missing/stale/unrecognized policy denies with no DB/chain side effect and records the policy/reason/audit correlation |
| F0-003 | Reviewed-document, beneficial-owner, jurisdiction and fresh-screening KYC evidence | Blocked — decision required | BLOCKED_DECISION | Decide checklists, review/acceptance, cadence, EDD, beneficial-owner completeness/source and retention; uploaded legacy documents remain unreviewed and the operation gate denies |
| F0-004 | Explicit immutable economic terms, scales, currencies, calendars and rounding | Proposed, changes noted (unreviewed) | PROPOSED | Build only immutable/versioned schema and exact-conversion/calculation framework; migrate current terms as `LEGACY_UNVERIFIED` and do not invent bond/NAV conventions |
| F0-005 | Multi-dimensional settlement state and evidence model | Proposed, changes noted (unreviewed) | PROPOSED | Same safe boundary as M0-LEDGER; `LEGALLY_EFFECTIVE` remains unreachable without F0-001 and legacy `SETTLED` becomes `LEGACY_SETTLED_UNVERIFIED` |
| F0-006 | Authorized instruction/agreement and chronological register-change ledger | Blocked — decision required | BLOCKED_DECISION | Decide instruction/agreement/correction authority, signatures/evidence, sequencing and reversal per entry type/jurisdiction; generic append-only history cannot authorize a mutation |
| F0-007 | Chain finality and deployed bytecode/admin/configuration reconciliation | Blocked — decision required | BLOCKED_DECISION | M0-INDEXER-B may add provisional plumbing, but finality/checkpoint, trusted RPC/quorum, runtime/proxy/admin/owner/key and legal-reliance policies are unresolved |
| F0-008 | Verifiable payment/DvP settlement; production-disable simulated canonical mutations | Proposed, changes noted (unreviewed) | IN_PROGRESS | Default settings/schema to initial and immediate settlement false; party refs are unverified metadata; combine exact DvP legs with independent adapter evidence and no holder mutation without verified cash and delivery |
| F0-009 | Locked entitlement snapshot and independently verified corporate-action payments | Blocked — decision required | BLOCKED_DECISION | Decide record/ex-date authority, timezone/calendar, tax/withholding, blocked-holder suspense, corrections and defaulted payments; legacy entitlements remain unverified |
| F0-010 | Lending kill switch until legal/collateral controls and reconciliation exist | Proposed, changes noted (unreviewed) | IN_PROGRESS | Default backend/UI exposure off and fail closed; new markets pause supply and borrow by default while risk-reducing withdrawal/repay remain available; old markets require inventory/pause/unwind/replacement |
| F0-011 | Quarantine MiFIR and DAC8/KStTG outputs as draft/unvalidated | Proposed, changes noted (unreviewed) | SELF_ASSESSED | Default-off and prohibited when enabled in production; prototype namespaces and `DRAFT_UNVALIDATED`; transport-only states/events; 20 targeted unit/migration tests pass, including seeded PostgreSQL V17→V18. Official schemas, population, routing, receipts and legal sign-off remain blockers |
| F0-012 | Machine-readable claims registry with evidence, scope, owner, expiry and CI enforcement | Proposed, changes noted (unreviewed) | SELF_ASSESSED | Closed schema/validator, canonical record and exact-text/file hashes, append-only base comparison, expiry/independence checks, a single allowlisted immutable-migration exception, fail-closed repository scan, and mandatory gating CI evidence was self-assessed by an automated contributor with no external review. Current rerun: verifier/regressions, ERC-3525 (17/17), backend reporting (20/20 including PostgreSQL migration), and full subgraph static/codegen/WASM gates pass. This is governance, not legal certification |

## Baseline evidence

| Surface | Baseline result | Finding |
|---|---|---|
| Backend `./mvnw verify -B` | Baseline passed outside the constrained sandbox; F0-011 combined targeted unit/migration suite passes 20/20 | Scheduled jobs continue after test application teardown, generate large database errors, and delay fork shutdown; actual JaCoCo is about 45.0% line / 38.6% branch versus a 36% / 23% gate and conflicting 70% documentation |
| Foundry `forge test -q` | 449 passed, 31 skipped after the first approved batch; independent rerun exited 0 | Regression tests now cover ERC-3525 address-transfer conservation, ERC-7540 synchronous bypass, repo withdrawal rounding and unique market valuation |
| Cairo `snforge test` | 29/29 passed | Cairo surface still needs domain/security review |
| Confidential relayer | Lint passed; 33/33 tests passed | Dependency audit reported 21 high-severity findings; lockfile was absent before baseline install |
| EVM subgraph | 16 ABI contracts / 71 handlers, 25 projection-status entities, multi-instance renderer, codegen, all mapping builds and labelled deployment gates pass | Reorg/finality, live replay/reconciliation and RPC bytecode identity remain pending; dependency audit still reports 45 findings including two critical |
| Operator/investor Angular apps | CI commands fail | Both workflows call a missing lint target; Karma expects missing `karma-jasmine`; no spec files were found |
| Docusaurus docs | English and German production builds pass after truth/build corrections | Dependency audit still reported 37 findings including two critical |
| DAML | Not run | `dpm` is unavailable in the current environment |

## Known deployment and operations blockers

- Helm combines a single `ReadWriteOnce` wallet volume with 3–10 anti-affined replicas.
- Ingress routes directly to the backend and bypasses Kong while the network policy does not admit the ingress-controller path.
- PostgreSQL secret keys referenced by Helm do not agree.
- Frontend JWTs are stored in `localStorage`; response hardening headers are incomplete.
- Promtail, Kong metrics, backup alerts, and pushgateway assumptions do not form a working monitoring path.
- A raw single deployment key has no documented multisig/timelock handoff.
- There is no CI coverage for shared frontend code, the relayer, Cairo, DAML, several indexers, documentation, Compose/Kong, or Helm.

These remain release blockers until their phase verdict and verification evidence are recorded here.
