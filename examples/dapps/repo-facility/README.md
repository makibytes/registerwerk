# eWpG Repo & Lending Facility

Reference marketplace dApp for **exit liquidity via collateral** — the compliant
alternative to a secondary market. A holder pledges a Registerwerk security-token
position (a bond, equity, or note) and draws a stablecoin loan against it, keeping the
position and its coupon/redemption rights intact. This is the same mechanism that
actually makes TradFi bond markets liquid: repo and securities lending, not order-book
trading. Registerwerk deliberately does not operate a secondary-market matching engine —
see [`docs/platform/defi-interoperability.md`](../../../docs/platform/defi-interoperability.md)
for the full research and design rationale.

| | |
|---|---|
| Slug | `repo-facility` |
| Category | `lending` |
| Contract | [`contracts/src/examples/EwpgRepoFacility.sol`](../../../contracts/src/examples/EwpgRepoFacility.sol) |
| Tests | [`contracts/test/examples/EwpgRepoFacility.t.sol`](../../../contracts/test/examples/EwpgRepoFacility.t.sol) |
| Manifest | [`backend/src/main/resources/demo/dapps/repo-facility.manifest.json`](../../../backend/src/main/resources/demo/dapps/repo-facility.manifest.json) |
| Deploy script | [`contracts/script/DeployLiquidityDapps.s.sol`](../../../contracts/script/DeployLiquidityDapps.s.sol) |

## What it showcases: asymmetric gating for maximum liquidity

Two sides, deliberately gated differently:

| Side | Function | Gating | Why |
|---|---|---|---|
| Lender | `deposit` / `withdraw` | **None** — open to any stablecoin holder | Lenders only ever hold a claim on pooled stablecoin; they never touch the restricted security token, so there's no securities-law reason to gate them. Widening this side as much as possible is exactly what deepens the pool. |
| Borrower | `pledgeAndBorrow` | `repo-facility.borrow` permission + KYC claim | Only a verified investor may pledge the restricted collateral asset. |
| Anyone | `repay` / `liquidate` | **None** at the ecosystem layer | The collateral transfer is subject to the token's T-REX identity-registry check. That check does not establish legal, custody, licensing, collateral, insolvency, or KYC sufficiency; production liquidation remains blocked pending those decisions. |

Interest accrues via the standard Aave-style index model (`liquidityIndex`/`borrowIndex`,
WAD-scaled, utilization-based rate) so both sides settle in O(1) regardless of
participant count.

## Prerequisite: nominee-pool flag on the collateral token

Like [`CompliantSecondaryMarket`](../../../contracts/src/examples/CompliantSecondaryMarket.sol),
this facility pools many borrowers' collateral behind its own single contract address.
Before any pledge past the first investor's individual cap can succeed, the collateral
token's registry agent must flag the facility as a nominee pool:

```solidity
EwpgComplianceModule(complianceAddress).setNomineePool(collateralToken, address(repoFacility), true);
```

## Deploying

```bash
# EwpgRepoFacility and EwpgPaymaster are pragma ^0.8.36 with no erc3643 dependency, so they
# ship in their own script — see DeployLiquidityDapps.s.sol's NatSpec for why they can't
# share a compilation unit with DeployExampleDapps.s.sol (which pins erc3643's IERC3643,
# exact-versioned =0.8.30, incompatible with ^0.8.36 in one file).
REGISTRY_WALLET_PRIVATE_KEY=0x... \
  PERMISSION_ORACLE_ADDRESS=0x... \
  forge script script/DeployLiquidityDapps.s.sol --rpc-url <rpc> --broadcast

# Then, per collateral asset (operator-only):
#   EwpgComplianceModule(compliance).setNomineePool(token, address(repoFacility), true)
#   EwpgRepoFacility(repoFacility).setCollateralConfig(token, pricePerUnit, maxLtvBps,
#       liquidationThresholdBps, liquidationBonusBps, true)
```

Run `test/examples/EwpgRepoFacility.t.sol` first to see the whole flow — deposit, pledge,
borrow, accrual over time, repay, and liquidation of an unhealthy position — before
deploying for real.

## Permissions declared in the manifest

| Code | Rationale |
|---|---|
| `repo-facility.borrow` | Pledge a KYC'd investor's security-token holding as collateral and draw a stablecoin loan against it |
| `repo-facility.configure` | Operator-only: enable a collateral asset and set its price mark, max LTV, liquidation threshold and liquidation bonus |

Claim topics: `1` (KYC) — only the borrower side requires it; the lender side is
intentionally ungated.

## Reference implementation versus deployment prerequisites

`EwpgRepoFacility` keeps 100% of borrower interest flowing to depositors (no protocol
reserve cut) and only supports full-close-factor liquidation, both deliberate
simplifications for auditability documented in the contract's own NatSpec. Lending
against securities as collateral also triggers its own regulatory layer (custody
segregation, margin-lending licensing, rehypothecation rules) independent of the
smart-contract design — get jurisdiction-specific legal review before operating this
facility against real securities in production. See
[`docs/platform/defi-interoperability.md`](../../../docs/platform/defi-interoperability.md#lending-against-securities-as-collateral-whats-actually-implemented-vs-what-still-needs-legal-sign-off)
for the full list.

## Publishing this dApp

As with `boardroom` and `bond-desk`, only the manifest and Solidity source are shipped
here — no real container images (the `images[]` digests are illustrative placeholders).
See [`docs/platform/dapp-development.md`](../../../docs/platform/dapp-development.md) for
the publication workflow. The demo environment (`SEED_DEMO_DATA=true`) seeds this dApp as
an already-`PUBLISHED` marketplace listing.
