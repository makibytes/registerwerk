# eWpG Bond Desk

Reference marketplace dApp for a **German eWpG-registered bond issued as a full
ERC-3643 (T-REX) security token**. The desk acts as paying agent: it issues bond units
to verified investors, computes and records per-holder coupon obligations each period,
and redeems (burns) matured positions.

| | |
|---|---|
| Slug | `bond-desk` |
| Category | `capital-markets` |
| Contract | [`contracts/src/examples/EwpgBondDesk.sol`](../../../contracts/src/examples/EwpgBondDesk.sol) |
| Tests | [`contracts/test/examples/EwpgBondDesk.t.sol`](../../../contracts/test/examples/EwpgBondDesk.t.sol) |
| Manifest | [`backend/src/main/resources/demo/dapps/bond-desk.manifest.json`](../../../backend/src/main/resources/demo/dapps/bond-desk.manifest.json) |
| T-REX bond deploy script | [`contracts/script/DeployEwpgTrexBond.s.sol`](../../../contracts/script/DeployEwpgTrexBond.s.sol) |
| dApp deploy script | [`contracts/script/DeployExampleDapps.s.sol`](../../../contracts/script/DeployExampleDapps.s.sol) |
| T-REX bootstrap helper | [`contracts/test/helpers/TrexSuiteDeployer.sol`](../../../contracts/test/helpers/TrexSuiteDeployer.sol) |

## What it showcases: two stacked authority layers

This is the deeper example — unlike `boardroom`, it wires a **real ERC-3643 (T-REX)
token suite** (implementation authority, ONCHAINID factory, identity registry, modular
compliance with `EwpgComplianceModule`) and demonstrates that a production eWpG
instrument stacks two independent layers of authority:

1. **ERC-3643 agent authority** — *what the desk contract itself may do to the token.*
   Granted once, by the token owner, via `AgentRole(bond).addAgent(address(desk))`. This
   is standard T-REX access control and has nothing to do with Registerwerk's ecosystem.
2. **Ecosystem permission authority** — *which org/human may trigger the desk.*
   Enforced entirely through `RegisterwerkGated`: org membership, the `PermissionOracle`,
   and ONCHAINID claim topics on the *operating org's* identity — not the investor's.

Both layers are independently enforced and independently tested
(`test_issue_revertsWhenInvestorNotVerifiedOnTrex` proves ecosystem gating alone isn't
enough; the T-REX layer rejects a mint to a wallet with no ONCHAINID regardless).

| Function | `requiresPermission` | `requiresClaim` | T-REX layer |
|---|---|---|---|
| `issue(investor, amount)` | `bond-desk.issue` | KYC (topic 1) | `bond.mint` — reverts if the investor isn't verified/compliant |
| `payCoupon(holders[])` | `bond-desk.pay-coupon` | KYC (topic 1) | Reads `balanceOf` only; no token mutation |
| `redeem(holder)` | `bond-desk.redeem` | AML (topic 2) | `bond.burn` at/after maturity |

Coupon payment is deliberately **on-chain-obligation-only**: `payCoupon` computes and
emits the amount owed per holder from their live bond balance, but actual cash
settlement happens off-chain through the registry backend — mirroring how Registerwerk
already separates on-chain token events from off-chain settlement status for trade
executions.

## Bug fixes exercised by this example

Building this example against a real, fully-wired T-REX suite surfaced two real
correctness issues in the existing eWpG contracts, both fixed as part of this change and
regression-tested here:

- **`EwpgComplianceModule.moduleCheck` didn't enforce blocked countries.** The mapping
  was written and readable but never consulted on transfer/mint — see
  `test_compliance_blocksAndUnblocksMintToBlockedCountry`.
- **Investor-count tracking double-counted top-ups and missed transfers-in.** Fixed with
  explicit membership tracking — see `test_investorCount_tracksTransitionsAcrossMintTransferBurn`.
- **`EwpgERC3643.assetId` was `immutable`**, which is silently wrong for a contract
  deployed once as the shared T-REX *implementation* behind every token proxy (immutable
  values are baked into the logic contract's bytecode, so every proxy sharing it would
  have returned the same value via delegatecall). Fixed with a one-time `setAssetId`
  initializer called by `EwpgTREXFactory.deployEwpgSuite` in the same transaction as
  deployment.

## Deploying a real bond

```bash
# 1. Deploy the T-REX bond suite (implementation authority, ONCHAINID factory,
#    identity registry, compliance module, the bond token itself).
REGISTRY_WALLET_PRIVATE_KEY=0x... \
  forge script script/DeployEwpgTrexBond.s.sol --rpc-url <rpc> --broadcast

# 2. Add the desk as a T-REX agent on the bond token once you know its address
#    (token owner only — see test/examples/EwpgBondDesk.t.sol for the reference call).

# 3. Deploy the dApp itself against the ecosystem's PermissionOracle and the bond token.
REGISTRY_WALLET_PRIVATE_KEY=0x... \
  PERMISSION_ORACLE_ADDRESS=0x... \
  EWPG_BOND_TOKEN_ADDRESS=0x... \
  forge script script/DeployExampleDapps.s.sol --rpc-url <rpc> --broadcast
```

Run `test/examples/EwpgBondDesk.t.sol` first if you want to see the whole flow —
including investor onboarding (real ONCHAINID identities, real ECDSA-signed KYC/AML
claims via a `ClaimIssuer`) — before deploying for real.

## Permissions declared in the manifest

| Code | Rationale |
|---|---|
| `bond-desk.issue` | Mint new bond units to a KYC'd investor |
| `bond-desk.pay-coupon` | Record the per-holder coupon obligation for a period |
| `bond-desk.redeem` | Burn a matured holder's position at redemption |

Claim topics: `1` (KYC), `2` (AML).

## Publishing this dApp

As with `boardroom`, only the manifest and Solidity source are shipped here — no real
container images (the `images[]` digests are illustrative placeholders). See
[`docs/platform/dapp-development.md`](../../../docs/platform/dapp-development.md) for the
publication workflow. The demo environment (`SEED_DEMO_DATA=true`) seeds this dApp as an
already-`PUBLISHED` marketplace listing.
