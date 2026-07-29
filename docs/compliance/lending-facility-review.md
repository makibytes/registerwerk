---
title: Repo/Lending Facility Compliance Review
description: Prioritized compliance findings for EwpgRepoFacility and the EwpgRepoMarket/Vault/oracle stack, with per-jurisdiction mapping and hardening status.
---

# Repo/Lending Facility Compliance Review

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records technical findings and intended control mappings. It is not evidence of
    legal compliance, regulatory authorisation, product approval, or production readiness.
    Repo, securities lending, collateral, custody, liquidation, insolvency, and reuse questions
    require an operator-, product-, instrument-, transaction-, and jurisdiction-specific review
    by qualified counsel and the responsible risk owners.

Review date: 2026-07-21. Scope: `contracts/src/examples/EwpgRepoFacility.sol`, the isolated-market
evolution under `contracts/src/lending/` (`EwpgRepoMarket`, `EwpgRepoMarketFactory`,
`EwpgRepoVault`, `oracle/RegisterwerkNavOracle`), and the backend `lending` read-model module.
Companion to [DeFi Interoperability](../platform/defi-interoperability.md), which covers the
product/regulatory rationale for the facility's design; this document is the security/compliance
gap analysis.

Findings are ranked **P0** (must fix or get legal sign-off before production use against real
securities), **P1** (should fix; meaningful risk reduction, not release-blocking for a reference
implementation), and **P2** (documented for awareness; acceptable MVP boundary or requires a
larger design change than this pass's scope).

---

## Summary: shipped vs. still open

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Oracle price-deviation circuit breaker missing | P0 | **Fixed** — see below |
| 2 | Oracle staleness opt-in, not enforced for deployed markets | P0 | **Fixed** — see below |
| 3 | Jurisdiction-specific margin-lending legal review unperformed | P0 | **Open — counsel work** |
| 4 | `EwpgRepoVault` missing `ReentrancyGuard` | P1 | **Fixed** — see below |
| 5 | Collateral ledger can desync from token balance after a forced transfer | P1 | **Fixed** — see below |
| 6 | Backend `LendingPositionController` had no `@PreAuthorize` | P1 | **Fixed** — see below |
| 7 | On-chain `borrowPaused` never reached the backend/frontend | P1 | **Fixed** — see below |
| 8 | Liquidation not truly permissionless when verified-liquidator set is thin | P1 | **Open** |
| 9 | Nominee-pool flag is asserted off-chain only, unverified on-chain | P2 | **Open** |
| 10 | Borrower wallet freeze doesn't reach already-pledged collateral | P2 | **Open** |
| 11 | Permission-string / NatSpec mismatches (`repo-oracle.*`, `repo-vault.*` vs. actual `repo-markets.*` constants) | P2 | **Fixed** — comment-only |
| 12 | `EwpgRepoVault.totalAssets()` iterates the full market list unbounded | P2 | **Open** |

---

## P0 — must fix or get sign-off before production

### 1. Oracle price-deviation circuit breaker (fixed)

**Before:** `RegisterwerkNavOracle.pushPrice` (and `EwpgRepoFacility.updatePrice`) accepted any
nonzero price with no bound relative to the previous mark. A single compromised or fat-fingered
`PUSH_PRICE` key could mark collateral arbitrarily high — enabling over-borrowing that drains the
pool — or arbitrarily low, triggering mass unnecessary liquidations.

**Fix:** `RegisterwerkNavOracle.pushPrice` now reverts with `ExcessiveDeviation` if the new price
deviates more than `maxDeviationBps` (default 2000 = 20%, operator-adjustable via
`setMaxDeviationBps`) from the previous mark. The first-ever push for an asset is unbounded (no
prior mark to compare against). A separately-permissioned `pushPriceWithOverride` (gated by
`OVERRIDE_PRICE`, distinct from the ordinary `PUSH_PRICE`) exists for a legitimate large
repricing, so an ordinary NAV-feed automation key cannot bypass the breaker on its own.

**Not fixed in this pass:** `EwpgRepoFacility.updatePrice` (the older, pooled facility) still has
no deviation cap — the facility is treated as a frozen reference implementation, and the fix
went into the newer `EwpgRepoMarket`/oracle stack that supersedes it. If the facility remains in
production use, port the same breaker there.

Tests: `contracts/test/lending/RegisterwerkNavOracle.t.sol` (7 tests).

### 2. Oracle staleness opt-in, not enforced for deployed markets (fixed)

**Before:** `EwpgRepoMarket._currentPrice()` already rejected a stale mark when
`maxPriceAgeSeconds != 0`, but `0` (staleness disabled) was a valid constructor argument with no
guard preventing an operator from deploying a real market that way — accidentally, or via a
compromised operator key deliberately choosing to disable the one safeguard against a frozen or
withheld price feed.

**Fix:** `EwpgRepoMarket` itself is unchanged (direct construction with `maxPriceAgeSeconds == 0`
still works, intentionally, for unit tests). `EwpgRepoMarketFactory.createMarket` — the only path
that deploys a real, operator-approved market — now reverts with `InvalidMaxPriceAge` if
`maxPriceAgeSeconds == 0`.

Tests: `contracts/test/lending/EwpgRepoMarketFactory.t.sol::test_createMarket_revertsWithZeroMaxPriceAge`.

### 3. Jurisdiction-specific margin-lending legal review (open — counsel work)

**Finding, unchanged from the pre-existing `defi-interoperability.md` notice:** pledging a
security as loan collateral triggers a regulatory layer independent of the smart contract's
correctness — custody segregation rules, margin-lending licensing, and (depending on
jurisdiction) rehypothecation restrictions that a simple cash-secured loan never triggers.

| Jurisdiction | Regulator | Relevant regime | Status |
|---|---|---|---|
| `DE_EWPG` | BaFin | KWG margin-lending / Wertpapierleihe rules, eWpG custody | Unreviewed |
| `LU_CSSF` | CSSF | CSSF custodian/depositary rules on rehypothecation | Unreviewed |
| `FR_AMF` | AMF | CMF teneur de compte-conservation restrictions | Unreviewed |
| `LI_TVTG` | FMA | TVTG token-container custody segregation | Unreviewed |

**No amount of further contract hardening substitutes for this.** This finding is carried forward
unchanged — it is explicitly out of scope for a code-only compliance pass and requires
jurisdiction-specific outside counsel before operating either `EwpgRepoFacility` or
`EwpgRepoMarket` against real securities in production.

---

## P1 — should fix

### 4. `EwpgRepoVault` missing `ReentrancyGuard` (fixed)

**Before:** `EwpgRepoVault` was the only contract in the lending stack (`EwpgRepoFacility`,
`EwpgRepoMarket` both guard every state-mutating function) without `ReentrancyGuard` — its
inherited ERC-4626 `deposit`/`mint`/`withdraw`/`redeem` and its own `allocate`/`deallocate` all
make external token calls with no reentrancy protection at the vault layer.

**Fix:** `EwpgRepoVault` now inherits `ReentrancyGuard`. `deposit`/`mint`/`withdraw`/`redeem` are
overridden purely to add `nonReentrant` around the OZ implementation (no logic change);
`allocate`/`deallocate` gained the modifier directly.

Tests: existing `contracts/test/lending/EwpgRepoVault.t.sol` suite continues to pass unchanged
(the guard is additive; no behavioral change for legitimate callers).

### 5. Collateral ledger can desync from token balance after a forced transfer (fixed)

**Before:** An issuer/agent `forcedTransfer` or `forceBurn` on the collateral token (an eWpG §24
Berichtigung, or a court-ordered/AWG-GwG freeze action at the token layer) can move tokens out of
`EwpgRepoMarket`'s balance without going through `repay`/`liquidate` — the market's internal
`positions[borrower].collateralAmount` accounting has no way to observe this. Left unreconciled,
the recorded collateral exceeds what the market can actually deliver, so a subsequent
`repay`/`liquidate` either reverts or, worse, over-pays out of other participants' funds.

**Fix:** A new CONFIGURE-gated `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`
lets the operator correct a position's recorded collateral **down** to what a specific
forced-transfer transaction actually removed. The function takes the corrected amount as an
explicit parameter — rather than trying to infer it from the token's aggregate `balanceOf(this)`
— because that balance sums every borrower in the market, so only an off-chain reconciliation of
the specific forced-transfer transaction (the same operator act that ordered it) can correctly
attribute the reduction to one borrower. The on-chain invariant enforced is one-directional: the
call reverts (`ReconciliationWouldIncreaseCollateral`) if the new amount is not strictly lower
than the current recorded amount, so it can never fabricate collateral that was never pledged.

Tests: `contracts/test/lending/EwpgRepoMarket.t.sol` (`test_reconcileCollateral_*`, 4 tests).

### 6. Backend `LendingPositionController` had no `@PreAuthorize` (fixed)

**Before:** `GET /api/v1/lending/my-positions` and `/supply-positions` carried no
method- or class-level `@PreAuthorize`, unlike every other customer-facing controller in this
module. Scoping was purely implicit — an unauthenticated call resolved a null `appUserId` and
silently returned an empty list rather than being rejected outright.

**Fix:** `@PreAuthorize("isAuthenticated()")` added at the class level, matching the pattern used
by `PositionStatementController`/`SteuerbescheinigungController` for other customer-facing
read endpoints.

### 7. On-chain `borrowPaused` never reached the backend/frontend (fixed)

**Before:** `EwpgRepoMarket.borrowPaused` (and `LendingMarketStatus.PAUSED` in the backend
read-model) existed, but nothing ever set the DB-persisted status to `PAUSED` — the on-chain flag
and the read-model were disconnected, so a paused market still showed as `ACTIVE` everywhere, and
a trader's borrow attempt against it would simply revert on-chain with no advance warning.

**Fix:** `LendingMarketService.resolveEffectiveStatus` reads the on-chain flag live for any
market whose persisted status is `ACTIVE`, reflecting `PAUSED` in every list/detail response
without mutating the DB row (a live read, not a state change) — a chain-read failure falls back
to the persisted status rather than failing the whole listing, matching the same best-effort
pattern already used for position health-factor reads. The customer-facing borrow stepper now
shows an explicit "market is temporarily paused" state instead of letting the transaction revert.

Tests: `LendingMarketServiceTest` (`activeMarketReflectsOnchainBorrowPaused`,
`activeMarketStaysActiveWhenNotPaused`, `retiredMarketSkipsOnchainCheck`,
`onchainReadFailureFallsBackToPersistedStatus`).

### 8. Liquidation not truly permissionless when the verified-liquidator set is thin (open)

**Finding:** `liquidate` is nominally ungated at the `RegisterwerkGated` layer (documented as
"permissionless, like Aave, because the token's own T-REX wall does the compliance work for
free"). That is only half true: the wall gates the **recipient** of seized collateral (the
liquidator), not just the borrower. If the pool of T-REX-verified, non-frozen, non-country-blocked
addresses willing to liquidate is thin, an unhealthy position may have no eligible liquidator at
all — the position sits underwater, undercutting depositors, with no fallback path to close it.
A verified liquidator sitting near `maxBalancePerInvestor` on the collateral token is also blocked
from receiving seized collateral unless the liquidator itself is nominee-flagged.

**Recommendation:** design an agent-of-last-resort liquidation path (e.g. an operator-controlled
address pre-flagged as a nominee pool, empowered to liquidate and immediately re-distribute or
warehouse seized collateral) for markets where the verified-liquidator set cannot be assumed to
be deep. Not implemented in this pass — it is a new access-control design, not a bounded fix.

---

## P2 — documented, acceptable for now or requires larger design work

### 9. Nominee-pool status is asserted off-chain only (open)

The entire pooling model depends on an off-chain operator action
(`EwpgModularCompliance.setNomineePool`) flagging the market as a nominee pool on the collateral
token, plus off-chain look-through KYC/AML of the pool's own depositors
(see [DeFi Interoperability § nominee/omnibus bridge](../platform/defi-interoperability.md#the-nomineeomnibus-bridge-nominee_pool)).
Nothing in the lending contracts asserts on-chain that this flag was actually set before accepting
pledges — the first pledge that would exceed the per-investor cap simply reverts at the token
layer if the flag is missing, which is a functional safety net but provides no proactive signal.
**Recommendation:** an on-chain event correlating a market's deployment to its nominee-pool flag
(e.g. the factory reading and logging the flag at `createMarket` time) would improve auditability
without changing the security model. Deferred as a nice-to-have observability improvement, not a
gap in the compliance model itself.

### 10. Borrower wallet freeze doesn't reach already-pledged collateral (open)

Once collateral is pledged into a market, the pool contract — not the borrower — is the token's
registered holder. A subsequent §16 eWpG Sperrvermerk or AWG/GwG freeze on the borrower's own
wallet no longer gates that already-pledged collateral, since the freeze check runs against the
`from` address of a transfer, and the pool is the `from` for any subsequent movement. Whether this
satisfies the regulatory intent behind a wallet freeze is itself a legal question tied to finding
#3 above, not a smart-contract gap with an obvious code fix (freezing the *position* rather than
the wallet would require new state and a new enforcement point in every lending contract).
Documented for the legal review in finding #3 to consider explicitly.

### 11. Permission-string / NatSpec mismatches (fixed — comment-only)

Two doc-comment mismatches between the constant actually enforced and the string documented in
NatSpec (a governance/audit-hygiene issue — the wrong string could mislead whoever grants
permissions by reading the docs rather than the code):
- `RegisterwerkNavOracle.pushPrice`'s doc comment said `repo-oracle.push-price`; the actual
  constant is `PUSH_PRICE = keccak256("repo-markets.push-price")`. Fixed.
- `EwpgRepoVault`'s contract-level doc comment said `repo-vault.curate`; the actual constant is
  `CURATE = keccak256("repo-markets.curate-vault")`. Fixed.

No behavioral change — both constants were already correct and namespaced under the `repo-markets`
marketplace listing per `ManifestValidationService`'s namespacing rule; only the prose was wrong.

### 12. `EwpgRepoVault.totalAssets()` iterates the full market list (open)

`totalAssets()` loops every market ever added (including disabled ones) on every share-price
computation — every `deposit`/`withdraw`/`mint`/`redeem` conversion pays this cost. For the
handful of markets a curator vault realistically manages this is immaterial, but unbounded market
growth would eventually become a gas-cost concern. Acceptable MVP boundary; a bounded/paginated
`totalAssets` (or excluding disabled markets from the loop) is a natural v2 refinement if a vault
ever approaches dozens of markets.

---

## Verification

- Contracts: `cd contracts && forge test --match-path "test/lending/*" -vv` — 45 tests, all
  passing (0 regressions against the pre-existing lending suite); full suite
  `forge test` — 388 passed, 0 failed, 18 skipped.
- Backend: `cd backend && ./mvnw verify` — 436 unit + 30 integration tests passing, all JaCoCo
  coverage gates (including the `registerstatement`/lending-adjacent compliance-critical floors)
  met.
