---
title: Account Abstraction & Sponsored Transactions
description: ERC-4337 / EIP-7702 smart accounts, sponsored gas, passkeys, and gasless permits.
---

# Account Abstraction & Sponsored Transactions

Registerwerk supports ERC-4337 sponsored transactions, EIP-7702 delegated accounts, ERC-1271
wallet verification, and an on-chain passkey account. These capabilities are independent of
[DeFi interoperability](./defi-interoperability.md).

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
implements `isValidSignature` like any other smart-contract wallet. The delegate the customer
portal uses is viem's `Simple7702Account`. `EwpgPasskeyAccount` (below) is **not** a 7702
delegate: its passkey and guardian are per-deployment state, so an EOA delegating to it would
have no passkey at all (every signature fails closed) and would share one guardian with every
other delegating EOA.

`frontend-customer` centralizes injected-wallet operations in `WalletService` and implements
optional EIP-7702/ERC-4337 execution in `SponsoredTxService`. Sponsorship needs
`environment.bundlerUrl` and a **voucher** from the backend for each UserOperation (next
section). When the backend refuses a voucher, the service raises
`SponsorshipUnavailableError` with the reason; `sendWithSponsorshipFallback` then sends the same
call as an ordinary self-paid transaction and reports that it did. It never falls back silently.
The UI does not create or operate `EwpgPasskeyAccount` instances.

## `EwpgPaymaster` — sponsored transactions

`contracts/src/ecosystem/EwpgPaymaster.sol` is an ERC-4337 **verifying paymaster** (against
EntryPoint v0.8, for native EIP-7702 support) that sponsors gas for verified Registerwerk
customers.

**Vouchers.** A UserOperation is only sponsored if it carries a voucher signed by the
policy's registered voucher signer:

```
paymasterData = policyId (32) ‖ validUntil (6) ‖ validAfter (6) ‖ maxFeePerGasCap (16) ‖ signature (65)
```

The signature is an EIP-191 (`personal_sign`) signature over `EwpgPaymaster.getHash(...)`. That
digest covers every UserOperation field (sender, nonce, `keccak(initCode)`,
`keccak(callData)`, account gas limits, the paymaster verification and postOp gas limits,
`preVerificationGas` and `gasFees`) plus `block.chainid`, the paymaster address, the policy id,
the validity window and the gas-price cap, under the domain tag
`keccak256("EwpgPaymasterVoucher(v1)")`. It deliberately excludes the signature bytes: they sit
inside `paymasterAndData`, which is part of `userOpHash`, so a voucher cannot sign
`userOpHash`. A wrong signer returns `SIG_VALIDATION_FAILED` (the EntryPoint reports
`AA34`) and an expired voucher returns `AA32`. Validation also reverts when the policy is
inactive or unregistered, when `maxFeePerGas` is above the signed cap, when
`paymasterPostOpGasLimit` is below 50,000 gas, or when the sender is not an active KYC'd
member (defence in depth; the backend checks this too).

**Voucher issuer (backend).** `POST /api/v1/gas-sponsorship/vouchers` (customer JWT,
`asset/web/GasSponsorshipVoucherController`, `asset/internal/GasSponsorshipVoucherService`)
takes the deployment id and the prepared UserOperation. Before signing it checks:

- the deployment's effective policy is **active** in the database. Deactivating a policy stops
  vouchers immediately, even before the on-chain flag is changed.
- the sender is an **active member wallet of the caller's legal entity** on that chain.
- the sender **holds the asset**: it has an active register entry (not a nominee-pool row) of
  the caller's entity for the deployment's asset. First-time subscribers without a register
  entry are not sponsored and pay their own gas.
- **scope** (default until product decides otherwise): every call in the
  `execute`/`executeBatch` batch targets the deployment's token contract with zero value,
  and `initCode` is empty or the bare EIP-7702 marker. Factory deployments are refused.
- **gas**: `maxFeePerGas` ≤ `registerwerk.paymaster.max-fee-per-gas-cap-wei` (the cap is
  signed into the voucher), the summed gas limits ≤ `max-total-gas`, and postOp gas ≥ 50,000.
- the policy's **monthly cap** (`monthlyCapEth`). Each issued voucher counts at its worst-case
  cost (`Σ gas limits × maxFeePerGas`, the EntryPoint prefund) and is recorded in
  `gas_sponsorship_voucher`, so the cap is effective before any operation settles. A voucher
  counts once per `(policy, sender, UserOperation nonce)`: asking again for the same nonce
  replaces the earlier voucher. One legal entity may use at most
  `registerwerk.paymaster.entity-monthly-cap-share` (default 10 %) of the cap per month, so a
  single organisation cannot exhaust an issuer's budget for every other holder.

Each voucher is valid for `voucher-validity-seconds` (default 300) and emits the
`GAS_SPONSORSHIP_VOUCHER_ISSUED` audit event. The dev signing key is
`registerwerk.paymaster.voucher-signer-key` (`REGISTERWERK_PAYMASTER_VOUCHER_SIGNER_KEY`). It
is wrapped in the wallet module's `EvmSigner` abstraction; production moves it to KMS/HSM. It
must **never** be the claim-signing (trusted-issuer) wallet: a voucher key can spend
sponsorship budget. Blank disables sponsorship. Paymaster addresses are configured per chain
under `registerwerk.paymaster.addresses` (`PAYMASTER_<CHAIN>_<NETWORK>`). The on-chain
`policyId` for a `GasSponsorshipPolicy` row is `keccak256(id.toString())`.

**Budget accounting.**

- `registerPolicy(policyId, signer, orgCap)` records the caller as the policy's **funder**,
  the voucher signer and a non-zero per-org cap. It can fund the policy in the same call.
- `fundSponsorship(policyId)` tops up. Only the funder can call it, and only the funder can
  rotate the signer (`setPolicySigner`), because a signer can spend the policy.
- Validation **reserves** the operation's `maxCost` out of the policy balance, so several
  operations in one bundle cannot each pass against the same balance. It also checks the
  sender org's cap against spent + reserved + `maxCost`. The cap is keyed by `orgOf(sender)`,
  so fresh wallets of one org cannot multiply it.
- `postOp` never reverts. It books `min(maxCost, actualGasCost + (postOpGasLimit + 10,000) ×
  feePerGas)` and refunds the rest of the reservation. EntryPoint v0.7/v0.8 hand `postOp` the
  cost *before* postOp's own gas and the unused-gas penalty are added, so booking only
  `actualGasCost` would let the books drift above the real deposit. The booked amount is an
  upper bound; the small difference stays in the deposit as surplus.
- `depositSurplus()` = EntryPoint deposit − (Σ balances + Σ reservations). It must never go
  negative. Monitor it as `paymaster_deposit_minus_booked_wei` and alert below 0.

**Ownership and controls (on chain).**

- `setPolicyActive(policyId, bool)` is the on-chain kill switch. The funder or a
  `paymaster.configure` holder can call it.
- `withdrawPolicy(policyId, amount)` returns unreserved budget from the EntryPoint deposit.
  The funder or a `paymaster.configure` holder may trigger it, but it **always pays the
  recorded funder**. Nobody can redirect it.
- `addStake(unstakeDelaySec)` (requires `paymaster.configure`), `unlockStake()` and
  `withdrawStake()` manage the EntryPoint stake. The first staker is recorded as
  `stakeFunder`, and the stake is always returned to that address. Only `stakeFunder` may call
  `unlockStake()`: de-staking makes public bundlers drop the paymaster, so another
  `paymaster.configure` holder cannot start it.

!!! note "Operator org and sponsor permissions"
    The paymaster is constructed as `(oracle, entryPoint, operatorOrg)`. `paymaster.configure` (the
    kill switch, per-org cap, `withdrawPolicy` trigger and stake management above) only works for
    wallets of that `operatorOrg`; a foreign org holding the same permission cannot administer a
    policy or the stake. `registerPolicy` and `addStake` are gated the same way: `registerPolicy`
    needs the permission `paymaster.register-policy` through the caller's org, which the operator
    grants to each sponsor (its own org and the issuers'), so a wallet the operator has not approved
    cannot register or squat a published policy id.

**Operator UI.** `frontend-operator`'s asset detail page has a **Gas Sponsorship** tab per
deployment (set/remove a deployment-specific override). The customer detail page has one for
issuers (set the issuer-level default that new deployments inherit). Both are backed by
`core/api/gas-sponsorship.service.ts`. The asset tab also shows the policy's on-chain state:
active flag, available and reserved balance, per-org cap, funder and voucher signer
(`GET /assets/{id}/deployments/{depId}/gas-sponsorship/onchain`). It warns when a policy is
deactivated in the database but still active on chain. `GET /gas-sponsorship/voucher-signer`
returns the address each policy must register as its signer.

- Deploy script: `contracts/script/DeployLiquidityDapps.s.sol` deploys `EwpgPaymaster` with
  EntryPoint `ERC4337Utils.ENTRYPOINT_V08` alongside `EwpgRepoFacility`.
- Demo data: `EcosystemDemoDataSeeder` seeds three `GasSponsorshipPolicy` rows — Meridian
  Capital's own issuer-level default (`ISSUER` sponsor), Aurora Finance's issuer default funded
  by the operator instead (`OPERATOR` sponsor, showcasing the other sponsor type), and a
  deployment-level override on Meridian's flagship Green Bond deployment (`OPERATOR`,
  demonstrating override-over-default precedence).
- Tests: `contracts/test/ecosystem/EwpgPaymaster.t.sol` runs every sponsored path through
  the **real EntryPoint v0.8.0** `handleOps` (vendored test-only under
  `contracts/test/aa-v08/`), including regressions for the drain scenarios listed under rollout below.
  `backend/.../asset/internal/GasSponsorshipVoucherServiceTest.java` and
  `unit/GasSponsorshipVoucherDigestTest.java` pin the Java digest to the Solidity one with a
  shared test vector.

### Stake, rollout and retiring the previous paymaster { #paymaster-operations }

Validation writes storage (reservations) and reads other contracts (`PermissionOracle`), so
under ERC-7562 public bundlers accept the paymaster only when it is **staked**. Per chain:

| Chain | Suggested stake | Unstake delay |
|---|---|---|
| Ethereum mainnet | ≥ 1 ETH | ≥ 1 day (86,400 s) |
| L2s (Base, Arbitrum, Optimism, Polygon) | follow the bundler's minimum (commonly 0.1–1 of the native token) | ≥ 1 day |
| Testnets | bundler minimum | ≥ 1 day |

Check the bundler provider's published minimum before staking. A stake with a shorter delay
than the bundler requires is treated as unstaked.

Rollout:

1. The paymaster deployed before this change (HEAD `b810acb` and earlier) is immutable and
   unsafe: any member could spend any policy, the gas price was unbounded, and a bundle could
   overspend. **Stop funding it now.** There is no withdraw function, so no funding affordance
   points at it any more.
2. Deploy the new `EwpgPaymaster` with its `operatorOrg` (`PAYMASTER_OPERATOR_ORG` in `DeployLiquidityDapps.s.sol`, default the deployer's org). Call `addStake` from the operator wallet and set
   `registerwerk.paymaster.addresses.<chain>` and the voucher signer key.
3. The operator grants `paymaster.register-policy` to each sponsor org (its own and the issuers') and `paymaster.configure` to the operator org only. Each funder then calls `registerPolicy(keccak256(policyRowId), voucherSigner, orgCap)` with the
   budget.
4. Record any ETH left in the old paymaster (`EntryPoint.balanceOf(old)`) per chain as a
   **stranded balance**. It can only be consumed by sponsored operations, which must not be
   done given the defects above.

Known limitation: `registerPolicy` is first-come for a policy id among approved sponsors. A
sponsor that front-runs the registration can block that id (it cannot take funds); the funder then
registers the policy under a new row id. Wallets without `paymaster.register-policy` can no longer
do this.

## `EwpgPasskeyAccount` — passkey signers for retail

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` is a minimal ERC-4337 smart account secured by
a WebAuthn/secp256r1 passkey instead of a seed-phrase-managed ECDSA key, composing three pieces
already vendored via `contracts/lib/openzeppelin-contracts` (no new dependency): OZ's `Account`
(ERC-4337 `validateUserOp`), `SignerWebAuthn` (passkey signature verification), and `ERC7821`
(minimal batch execution). It also implements ERC-1271 so it binds as a Registerwerk member
wallet exactly like any other smart-contract wallet. It is deployed as a fresh account per
customer and is **not an EIP-7702 delegate**: with no passkey in the account's own storage,
signature validation fails closed.

The guardian is an explicit constructor argument, never the deployer by accident. Calls can be
classified as routine, admin or recovery by target and selector. EntryPoint/ERC-7821 batches
reject admin and recovery operations, so a compromised session passkey or sponsored user
operation cannot perform them. `guardianExecute` is a **full custodial override**, not a
protective-only path: the guardian can make any call from the account without a timelock or
the passkey's co-signature, and it sets the call-role table itself. Whether a registry-held
guardian with unilateral control over retail accounts is intended is an open custody decision
(licensing and disclosure). Until it is decided, treat the guardian key as custody of the
account's assets.

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
and its inline comment). `test_eip7702DelegateHasNoSignerAndFailsClosed` shows that an EOA
delegating to an instance has no signer and cannot be controlled by the instance's deployer.

## Gasless permits

`EwpgBondDesk.subscribeWithPermit` spends a signed EIP-2612 `permit` instead of requiring a
separate prior `approve` transaction — halves the transaction count and pairs naturally with
`EwpgPaymaster` sponsorship (permit + sponsored execution = zero-gas-token UX). `MockStablecoin`
now implements `ERC20Permit` so the example/tests can exercise this end to end
(`test_subscribeWithPermit_succeedsWithoutPriorApproval` in
`contracts/test/examples/EwpgBondDesk.t.sol`). Not every real payment rail supports this: USDC
implements EIP-2612 natively; verify AllUnity Euro's support before wiring `subscribeWithPermit`
up against it in production — the plain `subscribe` path remains available either way.

## Signature formats

Wallet binding and manifest signing use `personal_sign`. `WalletSignatureVerifier` accepts that
wire format for EOAs and ERC-1271 wallets; it does not accept EIP-712 typed-data signatures.
