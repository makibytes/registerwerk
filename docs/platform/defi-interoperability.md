# DeFi Interoperability

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page is a technical design discussion. Registerwerk and the described DeFi, trading,
    custody, nominee, payment, and lending arrangements are not represented as legally permitted,
    compliant, or authorised, and are not production-ready. Classification and legal effect require an operator-,
    instrument-, service-, counterparty-, transaction-, and jurisdiction-specific review.

Registerwerk is intended for regulated capital-markets use. This document explains where and why
bridges to the DeFi/Ethereum ecosystem make sense, and — just as importantly — where they
don't, given the regulatory reality of tokenized securities rather than crypto-assets.

## The regulatory starting point

MiCAR/MiFID II classification cannot be inferred from a token standard, an `eWpG` label, or a
payment-rail flag. The `payment` module stores operator-entered disclosure and attestation fields;
it does not independently establish that an asset is a financial instrument, that a stablecoin is
an EMT, or that an issuer or service provider is authorised.

Whether a permissionless AMM, lending pool, custodian, nominee, or omnibus structure may hold or
transfer an instrument is a legal, regulatory, custody, insolvency, and product-design question,
not one that the smart contracts or jurisdiction configuration answer. The models below are
technical options for counsel and control-owner review.

## Jurisdiction interoperability matrix

Modeled in `backend/.../kyc/api/JurisdictionRequirementConfig.ComplianceMetadata` via the new
`defiInteropModel` / `permissionlessAmmAllowed` fields (`DefiInteropModel` enum, same package):

| Jurisdiction | Regulator | `defiInteropModel` | `permissionlessAmmAllowed` | Basis |
|---|---|---|---|---|
| `DE_EWPG` | BaFin | `NOMINEE_POOL` | `false` | eWpG Sammelverwahrung (collective custody) |
| `LU_CSSF` | CSSF | `NOMINEE_POOL` | `false` | CSSF-supervised custodian/depositary omnibus holding |
| `FR_AMF` | AMF | `NOMINEE_POOL` | `false` | CMF teneur de compte-conservation (account-keeper/custodian) regime |
| `LI_TVTG` | FMA | `NOMINEE_POOL` | `false` | TVTG token-container model + licensed VT Service Provider |

All four jurisdictions land on the same model today because all four already recognize a
licensed-intermediary omnibus structure — there was no jurisdiction-specific divergence to model
yet. `permissionlessAmmAllowed` is `false` everywhere and deliberately explicit (not just the
absence of a `true`) so a future jurisdiction addition has to make an active decision rather
than silently inheriting a default. Scope for this pass was intentionally limited to the four
jurisdictions already onboarded (`DE_EWPG`, `LU_CSSF`, `FR_AMF`, `LI_TVTG`); adding non-EU
regimes (e.g. Switzerland's DLT Act, which has its own DLT trading facility license covering
combined trading/settlement/custody) is a natural next addition once Registerwerk actually
operates there.

A third model, `ORACLE_ONLY`, exists for the (already-shippable, zero-custody-risk) pattern
where an external protocol only *reads* `PermissionOracle` claims — see
[dapp-development.md § External DeFi composability](./dapp-development.md#external-defi-composability).
No jurisdiction is restricted to `ORACLE_ONLY` today since `NOMINEE_POOL` is a strict superset
of what it allows.

## The nominee/omnibus bridge (`NOMINEE_POOL`)

A new ONCHAINID claim topic, `NOMINEE` (topic 4, alongside the existing 1=KYC/2=AML/
3=Accreditation), is issued by a trusted issuer to a licensed custodian/CASP's own ONCHAINID —
using the exact same `ClaimIssuanceService`/`EcosystemTrustedIssuersRegistry` machinery already
in place for KYC/AML claims. That custodian's pool contract address is then flagged as a
nominee pool on the specific token's `EwpgComplianceModule`
(`contracts/src/compliance/EwpgModularCompliance.sol`, `setNomineePool`), which:

- **Exempts** the pool address from `maxBalancePerInvestor` and `maxInvestors` — the whole
  point, since a pool nets many LPs' economic exposure behind one address and a per-investor
  cap on that single address defeats the cap's regulatory intent (it would either block all
  pooling outright, or silently let the cap be circumvented by netting many investors behind
  one "investor").
- **Keeps** the country-block and transfer-cooldown checks unconditionally — the pool operator
  itself must still not be domiciled in a blocked jurisdiction.
- **Only applies to contract addresses** (`to.code.length > 0`) — flagging an EOA as a nominee
  pool is a no-op, since the whole exemption only makes sense for a genuine pooled-custody
  contract, not an individual's wallet.

Responsibility for the look-through KYC/AML of the pool's own underlying LPs sits entirely with
the nominee/custodian operator, off-chain — exactly as it does for a traditional custodian
bank's omnibus account today. Marking a dApp's manifest with `requiredClaimTopics: [4]` is
surfaced to the reviewing operator during marketplace approval
(`ManifestValidationService`) as a flag requiring human review of the publisher's custodian
license, not an auto-approved declaration.

## What actually attracts liquidity providers and market makers

Before deciding what to build, it's worth being honest about what a trader or market maker
optimizes for, and how that maps — or doesn't — onto a compliant securities registry:

- **TradFi bond markets are not primarily liquid because of secondary trading.** They're
  liquid because of **repo** (a repurchase agreement: sell the bond now, agree to buy it back
  later at a fixed price) and **securities lending**. A dealer holding an illiquid position
  doesn't sell it to raise cash — it repos it out overnight or term, keeps economic exposure,
  and redeploys the cash. Repo markets move trillions daily, dwarfing secondary cash-bond
  trading volume, precisely because they let a holder access liquidity *without* an outright
  sale (no realized price, no lost upside, no forced-seller discount).
- **DeFi money markets (Aave, Compound) are the same mechanism, pooled and algorithmic**:
  deposit collateral, borrow against it, at a rate set by real-time utilization instead of a
  bilateral negotiation. What actually attracts LPs to a money market is a transparent,
  utilization-driven rate, permissionless supply-side entry, and a credible liquidation
  mechanism that keeps depositors whole.
- **Uniswap-style AMMs attract LPs through fee income and permissionless pool creation** — but
  that model assumes the traded asset is fungible, continuously priced, and safe to let an
  anonymous contract net-hold on behalf of many parties. None of that holds for a NAV-priced,
  eligibility-gated security, which is exactly why this repo rules out an AMM/order-book for the
  security-token leg (see the trading-mechanism section below).
- **Real-world-asset platforms already learned this lesson.** Ondo, Centrifuge, Maple, and
  BlackRock's BUIDL all derive most of their DeFi utility from being posted as **collateral**
  in a lending market, not from spot-trading liquidity — the tokenized RWA sits in one
  custodied/pooled position and the stablecoin liquidity moves around it.
- **What a market maker specifically wants**: a way to go both long and short, to hedge, to
  reuse the same capital across positions (capital efficiency), and certainty of execution.
  Collateralized borrowing gives a holder exactly that — leverage and capital efficiency —
  without Registerwerk ever having to operate a matching engine.

The conclusion: **a collateralized-lending reference facility is a potential liquidity feature
for Registerwerk, not a legally approved product.** It also fits the "don't
build a DEX" constraint perfectly, since collateralized lending was never an order book to
begin with.

## `EwpgRepoFacility` — legacy-named collateralized-lending example { #ewpgrepofacility-the-primary-exit-liquidity-mechanism }

Despite its historical contract name, `contracts/src/examples/EwpgRepoFacility.sol` is a reference collateralized-lending
facility with deliberately asymmetric gating. Production use is blocked pending legal
characterization, custody/control, liquidation, oracle, insolvency, and smart-contract approval:

It is **not** the platform's conventional repo implementation: it has no bilateral RFQ, private
dealer quote, title-transfer opening sale, fixed repurchase amount, margin-call workflow, or
two-party closing settlement. Those live in the separate [Repo Desk](../customer/lifecycle/repo-trading.md).

- **Lender side (`deposit`/`withdraw`) is open to any stablecoin holder** — no
  `RegisterwerkGated` check at all. Depositors only ever hold a claim on pooled stablecoin;
  they never touch the restricted security token, so there is no securities-law reason to gate
  them. This is the single biggest lever for "Registerwerk's attractiveness to gain liquidity
  in the market": the fewer barriers to *supplying* capital, the deeper the pool, since the
  risk being priced is entirely borne by the (gated) borrower side.
- **Borrower side (`pledgeAndBorrow`) is gated** — `repo-facility.borrow` permission plus a
  valid KYC claim — since only a verified investor may pledge the restricted collateral asset.
  A borrower pledges e.g. an `EwpgERC3643` bond position and draws stablecoin up to a
  configured loan-to-value ratio, keeping the bond position and its coupon/redemption rights
  intact. This is the "repo" trade: liquidity without a sale.
- **`repay` and `liquidate` are intentionally left ungated.** The collateral transfer back to
  the caller is itself subject to the token's own T-REX identity-registry check — an
  unverified caller's transaction simply reverts at the token layer. This means liquidation can
  be technically permissionless for eligible recipients, but that does not establish legal or
  regulatory compliance. The existing `isVerified()` wall supplies only a contract-level gate. Repayment is left
  open on principle — reducing risk and reclaiming your own previously-pledged collateral
  should never be blocked by an administrative permission change.
- **Interest is utilization-based** (Aave-style `liquidityIndex`/`borrowIndex`, WAD-scaled),
  so both sides settle in O(1) regardless of participant count, and depositors see a
  transparent, market-clearing yield rather than a fixed rate.
- Like `CompliantSecondaryMarket`, the facility's own address pools many borrowers' collateral
  behind one address, so any `EwpgERC3643` collateral asset needs the same
  `EwpgComplianceModule.setNomineePool(token, address(facility), true)` flag before pledges
  past the first investor's individual cap can succeed.

### Trading mechanism, split by pair type

- **Security-token legs: RFQ/bilateral matching over `DvpSettlement`**
  (`contracts/src/examples/CompliantSecondaryMarket.sol`). No shared bonding curve — quotes are
  matched off-chain (or via a simple on-chain quote-posting function) and settled in one successful
  transaction via the existing `lockAsset`/`lockPayment`/`settle` primitives. Exact-leg behavior
  assumes tokens without transfer fees/rebases; finality and legal-register posting are separate. This avoids
  impermanent-loss and oracle-manipulation exposure on a NAV-priced, potentially illiquid bond
  — the same reason real regulated venues (SDX, EU DLT Pilot Regime MTFs) use order-book/RFQ
  pricing rather than constant-product curves for securities. **Its role is now better
  understood as price discovery feeding `EwpgRepoFacility.updatePrice`** (the last executed
  fill is a legitimate collateral mark) rather than as the primary liquidity venue — exactly
  how secondary bond trading mostly serves price discovery in TradFi while repo does the
  liquidity heavy lifting. Multiple competing nominee operators can each deploy their own
  instance and be flagged on the same token, so this is dealer-to-client-style competition
  among market makers, not a single monopoly desk.
- **Stablecoin-only legs: a plain constant-product AMM**
  (`contracts/src/examples/StablecoinAmm.sol`). Reserved for pairs where neither leg is a
  security (e.g. AUEUR/USDC, both declared via the `payment` module's
  `PaymentRailType.STABLECOIN` rail catalog) — the one place a familiar DeFi-native AMM is
  actually the lower-risk choice, since there's no securities-pricing-integrity concern at all.

All three reference dApps inherit `RegisterwerkGated` the same way as `BoardroomGovernance`/
`EwpgBondDesk`. `EwpgRepoFacility` additionally ships a full manifest, README, and demo seeding
like the other two flagship examples — see
[dapp-development.md § Reference example dApps](./dapp-development.md#reference-example-dapps).
`CompliantSecondaryMarket` and `StablecoinAmm` remain tested Solidity only (no manifest, not
seeded as marketplace listings).

## `EwpgRepoMarket` / `EwpgRepoVault` — legacy-named isolated lending markets

`contracts/src/lending/` is the Morpho-Blue-style evolution of `EwpgRepoFacility`, additive to
it (both can run against the same ecosystem — see `script/DeployRepoMarkets.s.sol`). Where the
facility pools every collateral type behind one shared cash pool and one shared index pair, each
`EwpgRepoMarket` isolates risk to exactly one `{loanToken, collateralToken}` pair, deployed via
`EwpgRepoMarketFactory` (CREATE2, operator-gated). `EwpgRepoVault` is the MetaMorpho-style
curator layer on top, routing lender deposits across multiple markets with per-market caps.
`RegisterwerkNavOracle`/`IRepoOracle` formalize the facility's NAV-push pattern as a standalone,
swappable interface. Same asymmetric gating as the facility (lender side open, borrower side
KYC+permission-gated, repay/liquidate ungated at this layer — the token's own T-REX wall is the
real gate); see each contract's own NatSpec for the mechanics.

This evolution resolves two of the three simplifications called out below for the facility:
a reserve factor (capped at 25%, operator-settable) and partial (50% close-factor, Aave-style)
liquidation both now exist in `EwpgRepoMarket` — the facility itself is unchanged and remains a
simpler reference implementation. The third point — jurisdiction-specific margin-lending legal
review — applies identically to both and is **still open**; see the review below.

## Compliance review (2026-07-21) — findings and hardening

A full compliance pass across `EwpgRepoFacility`, the `EwpgRepoMarket`/`Vault`/oracle stack, and
the backend `lending` read-model turned up the items below. Full detail, per-jurisdiction
mapping, and severity ranking: `docs/compliance/lending-facility-review.md`. Summary of what
shipped in this pass vs. what remains open:

**Hardened (this pass):**
- **Oracle price-deviation circuit breaker** — `RegisterwerkNavOracle.pushPrice` now rejects a
  push deviating more than an operator-configurable `maxDeviationBps` (default 20%) from the
  previous mark; a separately-permissioned `pushPriceWithOverride` exists for legitimate large
  repricings. Bounds the blast radius of a single compromised or fat-fingered NAV-feed key.
- **Mandatory oracle staleness for deployed markets** — `EwpgRepoMarket` itself still allows
  `maxPriceAgeSeconds == 0` (staleness check disabled) for direct-construction unit tests, but
  `EwpgRepoMarketFactory.createMarket` now rejects `0` — every operator-deployed market has a
  real freshness bound.
- **`EwpgRepoVault` reentrancy guard** — the vault was the one contract in the lending stack
  without `ReentrancyGuard` on its value-moving entry points (`deposit`/`mint`/`withdraw`/
  `redeem`/`allocate`/`deallocate`); now guarded like every other Ewpg* lending contract.
- **Collateral-ledger reconciliation** (eWpG §24 Berichtigung) — a new CONFIGURE-gated
  `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)` lets the operator
  correct a position's recorded collateral **down** (never up) after an agent `forcedTransfer`/
  `forceBurn` moves collateral out of the pool independent of `repay`/`liquidate`, closing a gap
  where the internal ledger could otherwise desync from the token's actual balance.
- **Backend `LendingPositionController` authorization gap** — `/api/v1/lending/my-positions` and
  `/supply-positions` carried no `@PreAuthorize`; now require authentication.
- **On-chain `borrowPaused` now reaches the backend/frontend** — `LendingMarketService` reads
  the flag live (best-effort; a chain-read failure falls back to the persisted status rather
  than failing the listing) and reflects it as `PAUSED`, closing the gap where the status
  existed in the model but nothing ever surfaced it. The borrow stepper now shows an explicit
  "market paused" state instead of letting a borrow attempt revert on-chain.

**Still open (see the review doc for full detail):**
- **Jurisdiction-specific margin-lending legal review** — unchanged from the facility (see
  below): custody segregation, margin-lending licensing, and rehypothecation restrictions are a
  regulatory question independent of the contract, and remain unreviewed for `DE_EWPG`/
  `LU_CSSF`/`FR_AMF`/`LI_TVTG`.
- **Liquidation is not truly permissionless when the verified-liquidator set is thin** — seized
  collateral is delivered to the liquidator, so the T-REX wall gates the liquidator too; with no
  eligible liquidator, an unhealthy position cannot be closed. No fallback (e.g. an
  agent-of-last-resort) liquidation path exists yet.
- **On-chain nominee-pool status is asserted off-chain only** — nothing on-chain verifies a
  market was actually flagged as a nominee pool before accepting pledges past the per-investor
  cap; the first over-cap pledge simply reverts at the token layer today.
- **Borrower wallet freeze does not reach already-pledged collateral** — once collateral is in
  the pool, a subsequent freeze on the borrower's own wallet no longer gates it, since the pool
  contract is the token's registered holder from that point on.

## Lending against securities-as-collateral: what's actually implemented vs. what still needs legal sign-off

`EwpgRepoFacility` is implemented and tested (`contracts/test/examples/EwpgRepoFacility.t.sol`)
as a **reference implementation** — the collateralized-lending mechanics, gating, and
liquidation logic are real and correct, but the following remain deliberate simplifications or
open questions before a production deployment:

- **No protocol reserve factor** — 100% of borrower interest flows to depositors today, kept
  that way for exactly auditable accounting in a reference implementation. A reserve cut is an
  isolated, additive change. (`EwpgRepoMarket` already adds one — see above.)
- **Full-close-factor liquidation only** — an unhealthy position is liquidated in one call for
  the full outstanding debt, not partially. Real money markets often support partial
  liquidation to reduce liquidator capital requirements. (`EwpgRepoMarket` already adds
  partial/close-factor liquidation — see above.)
- **Securities-as-collateral still triggers its own regulatory layer independent of the
  smart-contract design**: custody segregation rules, margin-lending licensing, and (depending
  on jurisdiction) rehypothecation restrictions that don't apply to a simple cash-secured loan.
  Get jurisdiction-specific legal review of margin-lending rules per `DE_EWPG`/`LU_CSSF`/
  `FR_AMF`/`LI_TVTG` before operating this facility against real securities in production —
  this is one of the more heavily regulated corners of MiFID II / national securities law, and
  the contract being correct does not substitute for that review. **Still unreviewed as of the
  2026-07-21 compliance pass above** — this is counsel work, not something further contract
  changes can substitute for.
