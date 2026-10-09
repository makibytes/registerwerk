# Glamsterdam

Glamsterdam is the Ethereum upgrade that pairs the *Amsterdam* execution-layer fork with the *Gloas*
consensus-layer fork. Registerwerk's contracts target **Glamsterdam chains only**: they are compiled for
the `amsterdam` EVM version and are not kept compatible with pre-Glamsterdam chains.

!!! warning "Status of the specification"
    This page reflects EIP-7773 (the fork's meta EIP) and the draft specifications of its EIPs as of
    October 2026. Sepolia activated the fork on 2026-10-06 (epoch 353024); Hoodi and mainnet have no
    activation time yet. Several EIPs below are still in *Review* or *Last Call* (deadline 2026-11-01),
    and the state-gas parameters of EIP-8037 and EIP-8038 are described by their authors as not final.
    Every number on this page was measured with Foundry 1.8.5 (`forge test`, `anvil --hardfork
    amsterdam`); re-measure before mainnet.

## What matters for Registerwerk

| EIP | Change | Effect on Registerwerk |
|---|---|---|
| [7954](https://eips.ethereum.org/EIPS/eip-7954) | Contract code up to 64 KiB (was 24 KiB), initcode up to 128 KiB (was 48 KiB) | Removes the last size exemption (`EwpgConfidentialFactory`, 27 KB). See [Contract size](#contract-size). |
| [8037](https://eips.ethereum.org/EIPS/eip-8037) | State creation is priced per byte (`CPSB` = 1,530 gas) and metered in its own gas dimension | **The most important change.** A new storage slot costs ~110k gas (was 22k), a deployed code byte 1,530 gas (was 200), a new account ~196k. See [Gas](#gas). |
| [8038](https://eips.ethereum.org/EIPS/eip-8038) | Cold account access 3,000 (was 2,600); a storage write costs 10,000 on top of the access (was 2,800) | Updating an existing slot costs ~12.1k (was ~5k). `EXTCODESIZE`/`EXTCODECOPY` cost more. |
| [2780](https://eips.ethereum.org/EIPS/eip-2780) | Resource-based intrinsic transaction gas | `forge script` under-estimates small calls (see [Tooling](#tooling)). |
| [7778](https://eips.ethereum.org/EIPS/eip-7778) | Block gas accounting without refunds | Users still get refunds; blocks do not count them. No contract change. |
| [7708](https://eips.ethereum.org/EIPS/eip-7708) | Every ETH transfer emits an ERC-20-shaped `Transfer` log from `0xff…fe` | The subgraph reads events per contract address and is unaffected. An indexer that filtered `Transfer` by topic only, without an address, would now see ETH transfers. |
| [7976](https://eips.ethereum.org/EIPS/eip-7976) | Higher calldata floor | Keep on-chain payloads to hashes and identifiers; nothing in the contracts takes large blobs. |
| [7981](https://eips.ethereum.org/EIPS/eip-7981) | Higher access-list cost | The backend does not attach access lists. |
| [7997](https://eips.ethereum.org/EIPS/eip-7997) | The CREATE2 factory at `0x4e59…956C` exists on every chain | The address Foundry uses for `new{salt}` is guaranteed; deployment scripts need no bootstrap. |
| [8024](https://eips.ethereum.org/EIPS/eip-8024) | `DUPN`/`SWAPN`/`EXCHANGE` lift the 16-slot stack limit | solc 0.8.37 does not emit them yet, so "stack too deep" workarounds (for example the optimizer for `EwpgPasskeyAccount`) stay. |
| [7843](https://eips.ethereum.org/EIPS/eip-7843) | `SLOTNUM` opcode (solc: `block.slotnum`) | Not needed. |
| [8246](https://eips.ethereum.org/EIPS/eip-8246) | `SELFDESTRUCT` no longer burns | No contract uses it. |

## Contract size

**EIP-7954 makes size a non-issue below 64 KiB, but not a free lunch.**

- `EwpgConfidentialFactory` embeds the creation code of both confidential tokens (27,493 bytes). It was
  the one contract over the old limit and an entry in the size gate's exemption list. It is now
  deployable as is and the exemption is gone. `scripts/check-contract-sizes.mjs` enforces the new limits
  (65,536 / 131,072 bytes) and prints what the largest contracts cost to deploy.
- The split of `AssetTokenFactory` into a coordinator plus one deployer module per token standard stays.
  Merging all six modules would exceed 64 KiB (their creation code adds up to ~74 KB) and, because every
  byte costs 1,530 gas whether it lives in one contract or six, would save only one account creation
  per merged module.
- Size now translates directly into deployment cost: `(120 + bytes) × 1,530` gas. A 10 KB token costs
  ~15.5M gas to deploy, the largest contract ~42M.

## Gas

Measured under the Amsterdam schedule (Foundry 1.8.5) against Osaka (the existing-slot row is computed from EIP-8038, not separately measured):

| Operation | Osaka | Amsterdam |
|---|---|---|
| `SSTORE` to a new slot | 22,181 | 110,101 |
| `SSTORE` to an existing slot, first change in the transaction | ~5,000 | ~12,100 |
| `CALL` to a cold account | 2,624 | 3,024 |
| Deploy 10,000 bytes of code | 2,034,742 | 15,500,220 |
| `new Probe()` (a small contract) | 280,989 | 1,479,061 |
| `TSTORE` + `TLOAD` | 112 | 112 |

Consequences, and what was changed:

- **ERC-4337 sponsorship would have failed.** The paymaster created two storage slots during
  validation (the budget reservations) and one during `postOp` (the org's cumulative spend). Fourteen
  paymaster tests failed with `AA26 over verificationGasLimit` on the first run under Amsterdam. The
  reservations now live in transient storage (they exist only between validation and `postOp` of one
  `handleOps` transaction, and the protocol clears them), `MIN_POST_OP_GAS_LIMIT` is 150,000 (was
  50,000; the measured minimum for the first sponsorship of an org is ~125k), and the account
  verification budget in the tests, and the frontend and backend defaults, were raised. With the old
  50,000 floor, `postOp` would run out of gas, the EntryPoint would swallow it, and the spend would
  silently not be booked.
- **Transient storage instead of storage for flags.** `ReentrancyGuard` became
  `ReentrancyGuardTransient` in six contracts (it created a slot at deployment, ~110k, and cost ~2.3k net per
  guarded call, 12k before refunds that blocks no longer count), and `EwpgERC3525`'s force-operation flag is transient (it created a slot in the first
  forced operation).
- **Fewer slots per trade.** `DvpSettlement`'s `Trade` occupies 5 slots instead of 7 (seller, expiry,
  leg and state share a slot; the two amounts share another): ~220k less gas per lock. Amounts are
  `uint128` (a lock above 2^128 − 1 base units is refused); `hashTerms` and the events are unchanged.
- **Backend gas limits.** The default ceiling `registerwerk.blockchain.fee-cap.max-gas-limit` is 60M
  (was 30M): a 15 KB token deployment costs ~24M gas and an estimate with the 20 % safety margin passes
  29M. The fallbacks used when a node cannot estimate are 40M for a deployment (was 5M) and 1.5M for a
  call (was 500k). The sponsored-transaction defaults in the customer portal were raised for the same
  reason.

### Further savings that are now worth their effort

These were measured but not done, because each changes more than gas:

- **Per-asset tokens as EIP-1167 clones.** Every asset token is a full contract deployment. A clone of a
  shared implementation costs ~0.25M instead of 12–24M. It needs initializers instead of constructors,
  which touches all six token standards, their tests and the CREATE2 address prediction.
- **Custom errors instead of revert strings in the token contracts.** Stripping the strings shrinks
  `EwpgERC20` by 1.4 KB (~2.1M gas per deployment), `EwpgERC3525` by 3.2 KB (~4.9M) and `EwpgERC7540` by
  3.2 KB (~4.9M). The backend decodes `Error(string)` and shows custom errors as a raw selector, so the
  backend needs an error table first.
- **One slot per position.** `EwpgRepoMarket.Position` and `EwpgRepoFacility.Position` hold two
  `uint256`; packing them into two `uint128` saves ~110k gas per new borrower.
- **`optimizer_runs`.** 20 instead of 200 shrinks the token contracts by about 2 % (≈0.3–0.6M gas per
  deployment) and makes calls marginally dearer.

## Tooling

- `contracts/foundry.toml` sets `evm_version = "amsterdam"` and `experimental = true` (solc 0.8.37 only
  offers the Amsterdam target as experimental). It needs Foundry 1.8.1 or newer; CI and the demo stack
  use 1.8.5.
- `anvil` runs with `--hardfork amsterdam --gas-limit 200000000` in `docker-compose.yml`. Several demo
  deployments exceed anvil's 30M default block gas limit.
- `forge script` needs `--gas-estimate-multiplier 200`. Its simulation under-estimates small calls under
  the Amsterdam schedule: a no-op `SSTORE` was estimated at 21,247 gas and ran out of gas on-chain at the
  default 130 % (29,346). Unused gas is refunded, so the headroom costs nothing. Both demo deployment
  scripts (`DeployLocalLendingDemo`, `DeployLocalTrexDemo`) were run end to end against an
  `anvil --hardfork amsterdam` with this setting.
- ERC-4337: a first operation of a sender creates the sender's nonce slot (~110k) inside the account's
  `verificationGasLimit`. Take the limits from the bundler's estimate; the static defaults are fallbacks.
