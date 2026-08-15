---
title: Account Abstraction & Sponsored Transactions
description: ERC-4337 / EIP-7702 smart accounts, sponsored gas, passkeys, and gasless permits.
---

# Account Abstraction & Sponsored Transactions

A separate opportunity from [DeFi Interoperability](./defi-interoperability.md): advances in
the Ethereum ecosystem since ERC-4337 (account abstraction) make it possible to sponsor gas for
retail/institutional customers and simplify their onboarding, independent of any DeFi bridge.
This was greenfield when this work started — no AA/paymaster/passkey code existed anywhere in
the repo.

## Foundation: `WalletSignatureVerifier`

`WalletSignatureVerifier` (`orgidentity/api/WalletSignatureVerifier.java`, backing
`orgidentity/internal/MemberWalletService` and `marketplace/internal/ManifestSigningService`)
verifies signatures via **either** ECDSA recovery (plain EOAs) **or** ERC-1271
`isValidSignature` (smart-contract wallets), based on the claimed address's on-chain code. This
is the prerequisite for everything below — without it, a smart account could never bind as a
member wallet or sign a marketplace manifest at all.

## EIP-7702: the smart-account on-ramp

EIP-7702 (live since the Pectra upgrade) lets an existing EOA delegate its code to a
smart-account implementation **while keeping the exact same address**. This is the natural
on-ramp for Registerwerk specifically, because every part of the existing model keys off a
fixed wallet address:

- `OrgRegistry._orgOf[wallet]` (`contracts/src/ecosystem/OrgRegistry.sol`) — one wallet, one org, by address.
- T-REX `IdentityRegistry.registerIdentity(address, ...)` — identity/claims registered per address.
- `EwpgCompliance.isWhitelisted(address)` — whitelist keyed by address.

A customer upgrading their existing EOA to a 7702-delegated smart account needs **zero
migration** of any of the above — the address doesn't change, so the org membership, identity
registration, and whitelist entries all stay valid. The only new requirement is
`WalletSignatureVerifier`'s ERC-1271 path (already in place), since a 7702-delegated EOA's code
implements `isValidSignature` like any other smart-contract wallet — including
`EwpgPasskeyAccount` below, which is exactly such a delegate implementation.

**Frontend implication:** `frontend-customer` still uses the injected-wallet path for its existing
org-identity operation — a single `window.ethereum.request(...)` call site
(`frontend-customer/src/app/features/company-admin/org-identity/org-identity.component.ts`).
Introducing 7702/passkey creation into that flow still needs a thin wallet layer;
`viem` (with its `viem/account-abstraction` and EIP-7702 helpers) is the recommended SDK, since
there is no existing ethers/wagmi dependency to preserve compatibility with. This remains the
one piece of this track that is UI work rather than a contract/backend change.

## `EwpgPaymaster` — sponsored transactions

`contracts/src/ecosystem/EwpgPaymaster.sol` is an ERC-4337 `IPaymaster` (against EntryPoint
v0.8, for native EIP-7702 support) that sponsors gas for verified Registerwerk customers:

- **Compliance-gated by who, not by what they call**: `validatePaymasterUserOp` checks
  `PermissionOracle.isActiveMember(userOp.sender)` and `hasClaimTopic(userOp.sender, KYC)` —
  never sponsors gas for a non-verified wallet. Because a 7702-delegated EOA keeps its
  original address, `userOp.sender` *is* the customer's existing member-wallet address, so this
  reads directly off the same oracle every other ecosystem contract uses. Parsing an arbitrary
  smart-account's `callData` to restrict *which contract* gets called is account-implementation-
  specific and intentionally out of scope — see the contract's NatSpec.
- **Sponsorship is scoped by an opaque `policyId`** (encoded in `paymasterAndData`), funded via
  `fundSponsorship(policyId)` by anyone willing to sponsor (the operator or an issuer's
  treasury) — both earmarking an internal budget and depositing into the EntryPoint.
- **A per-wallet spend cap** (`setWalletBudgetCap`) bounds how much of a *shared* policy budget
  any single wallet can consume, on top of the aggregate policy budget itself.
- Backed by the `deployment/api/GasSponsorshipPolicy` entity (backend) — mirrors the existing
  `MintControlRule` pattern: a per-deployment override, or an issuer-level default that future
  deployments from that issuer inherit until they get their own override
  (`GasSponsorshipService.resolveEffectivePolicy`, `asset/web/GasSponsorshipController`). The
  on-chain `policyId` for a given row is `keccak256(id.toString())`. This backend layer is
  configuration only — it does not yet drive an on-chain sync job pushing budgets into the
  paymaster automatically; an operator/issuer funds `EwpgPaymaster.fundSponsorship` directly
  today.
- Operator UI: `frontend-operator`'s asset detail page has a **Gas Sponsorship** tab per
  deployment (set/remove a deployment-specific override) and the customer detail page has one
  for issuers (set the issuer-level default new deployments inherit) — both backed by
  `core/api/gas-sponsorship.service.ts`, showing the currently-effective policy and whether
  it's an override or an inherited default.
- Deploy script: `contracts/script/DeployLiquidityDapps.s.sol` deploys `EwpgPaymaster` (default
  EntryPoint `ERC4337Utils.ENTRYPOINT_V08`) alongside `EwpgRepoFacility` — kept separate from
  `DeployExampleDapps.s.sol` since both are pragma `^0.8.36` and can't share a compilation unit
  with that script's erc3643-dependent imports (exact-pinned to `0.8.30`).
- Demo data: `EcosystemDemoDataSeeder` seeds three `GasSponsorshipPolicy` rows — Meridian
  Capital's own issuer-level default (`ISSUER` sponsor), Aurora Finance's issuer default funded
  by the operator instead (`OPERATOR` sponsor, showcasing the other sponsor type), and a
  deployment-level override on Meridian's flagship Green Bond deployment (`OPERATOR`,
  demonstrating override-over-default precedence).
- Tests: `contracts/test/ecosystem/EwpgPaymaster.t.sol` (against a minimal `MockEntryPoint` —
  see its NatSpec for why a full `handleOps` simulation isn't needed to test the paymaster's own
  accounting logic), `backend/.../unit/GasSponsorshipServiceTest.java`.

## `EwpgPasskeyAccount` — passkey signers for retail

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` is a minimal ERC-4337 smart account secured by
a WebAuthn/secp256r1 passkey instead of a seed-phrase-managed ECDSA key, composing three pieces
already vendored via `contracts/lib/openzeppelin-contracts` (no new dependency): OZ's `Account`
(ERC-4337 `validateUserOp`), `SignerWebAuthn` (passkey signature verification), and `ERC7821`
(minimal batch execution). It also implements ERC-1271 so it binds as a Registerwerk member
wallet exactly like any other smart-contract wallet.

Its deployer is the immutable guardian. Calls can be classified as routine, admin or recovery by
target and selector. EntryPoint/ERC-7821 batches reject admin and recovery operations; those must
use `guardianExecute`. This prevents a compromised session passkey or sponsored user operation
from changing high-risk registry controls.

Paired with `EwpgPaymaster`, a retail investor's onboarding-to-first-subscription flow needs no
seed phrase and no gas token — biometric passkey authentication plus sponsored execution. Note:
`contracts/foundry.toml` now enables the Solidity optimizer (`optimizer = true`,
`optimizer_runs = 200`, matching the vendored OZ library's own default) — WebAuthn signature
parsing hits "stack too deep" without it.

Tests (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) build real WebAuthn authentication
assertions using Foundry's native P256 cheatcodes (`vm.publicKeyP256`/`vm.signP256`), including a
worked example of the one non-obvious gotcha: `abi.encode(structValue)` adds an extra top-level
offset word for a struct containing dynamic fields, which `WebAuthn.tryDecodeAuth` doesn't
expect — encode the struct's fields as separate arguments instead (see the test's `_sign` helper
and its inline comment).

## Gasless permits

`EwpgBondDesk.subscribeWithPermit` spends a signed EIP-2612 `permit` instead of requiring a
separate prior `approve` transaction — halves the transaction count and pairs naturally with
`EwpgPaymaster` sponsorship (permit + sponsored execution = zero-gas-token UX). `MockStablecoin`
now implements `ERC20Permit` so the example/tests can exercise this end to end
(`test_subscribeWithPermit_succeedsWithoutPriorApproval` in
`contracts/test/examples/EwpgBondDesk.t.sol`). Not every real payment rail supports this: USDC
implements EIP-2612 natively; verify AllUnity Euro's support before wiring `subscribeWithPermit`
up against it in production — the plain `subscribe` path remains available either way.

## EIP-712 typed data — still deferred

Wallets render EIP-712 structured data far more legibly than opaque hex/plain strings. Worth
applying to any signed messages once a corresponding signing UI exists to render it — the
existing `personal_sign` wallet-binding challenge and manifest-signing flows were deliberately
left as-is in this pass, since migrating their wire format requires a frontend signing-method
change (`eth_signTypedData_v4`) that is out of scope here; introducing the format without that
caller would just be unused surface area.
