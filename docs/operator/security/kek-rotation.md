---
title: KEK rotation runbook
description: When and how to re-wrap wallet data keys under the current key-encryption key, what the endpoints do and do not change, and how to check the result.
---

# KEK rotation runbook

Software wallets keep their private key encrypted under a per-wallet **data key** (DEK). The DEK is stored wrapped by the platform **key-encryption key** (KEK) held by the configured provider (`REGISTERWERK_WALLET_KEK_PROVIDER`: `AWS_KMS`, `AZURE_KEY_VAULT` or `GCP_KMS`; `ENV_VAR` is development only and is refused in production mode). *Rotating the KEK* re-wraps every DEK under the KEK version that is currently active. It changes **no key material**: the wallet keys, their addresses and the contract roles granted to them stay as they are.

Wallets whose key is in a PKCS#11 HSM or in a cloud-KMS signer have no software key and are skipped, and so are legacy keystores that predate envelope encryption (no wrapped DEK).

## When to run it

| Situation | Action |
|---|---|
| Storage of one wallet's keystore may have been exposed (copied volume, restored backup in the wrong place) | Single wallet |
| The KEK is suspected compromised, or a provider key version is being retired | Whole fleet |
| Routine key-version rotation inside the provider | Usually nothing: GCP and AWS ciphertexts record which version wrapped them. Re-wrap only if you want old versions out of use so that they can be disabled |

!!! warning "A KEK compromise is not solved by re-wrapping alone"
    Anyone who copied the wrapped DEKs *and* could use the old KEK version has everything needed to recover the keys. Re-wrapping protects against future copies of the storage. If the KEK itself was compromised, also treat the wallet keys as exposed: generate new wallets, move registry roles and funds to them, and retire the old ones.

!!! danger "The same KEK also protects other secrets, and these endpoints do not re-wrap them"
    The platform KEK also wraps the data keys of operator TOTP secrets, webhook signing secrets and secure-link secrets. `rotate-kek` and `rotate-kek-all` re-wrap **wallet** keystores only; there is no tool that re-wraps the others. Disabling or destroying an old KEK version while any of those values is still wrapped under it makes them undecryptable: operators would lose their step-up second factor and webhook deliveries would fail. Until a re-wrap exists for them, keep old KEK versions enabled; rotation narrows what a *future* copy of the wallet storage exposes, it does not retire the old version.

## What the endpoints do

| Endpoint | Scope | Protection |
|---|---|---|
| `POST /api/v1/admin/wallets/{id}/rotate-kek` | One wallet | Step-up, reason `WALLET_KEK_ROTATION` |
| `POST /api/v1/admin/wallets/rotate-kek-all` | Every wallet | Step-up **and a second approver**, reason `WALLET_KEK_ROTATION_ALL` (see the [approval queue](approval-queue.md)) |

Both are for `REGISTRY_ADMIN`. Neither requires an `Idempotency-Key`; running a rotation again is harmless. The single call answers `{"rotated": true|false}`; the fleet call answers `{"rotatedWalletIds": [...], "rotatedCount": n}`. Each re-wrapped wallet writes a `WalletKekRotatedEvent` to the [audit log](../../platform/audit-log.md).

The endpoints re-wrap **under the key the backend is configured with**. How that behaves depends on the provider:

| Provider | Re-wrap | Consequence |
|---|---|---|
| `GCP_KMS` (`registerwerk.wallet.kms.key-id` is the key, without a version) | Decrypt, then encrypt under the key's primary version | Make a new version primary and run the rotation. Do not disable the old version (see the warning below) |
| `AWS_KMS` | `ReEncrypt` to the configured key id | Enable automatic key rotation in KMS, or re-encrypt to another key by changing `key-id` only if the old key can still decrypt the existing DEKs during the run. Test first |
| `AZURE_KEY_VAULT` (`key-id` includes a key version) | Unwrap and wrap with the same configured key version | The endpoint cannot move DEKs to a different key version: the configured version is the only one used to unwrap. Rotate the Azure key version through a planned migration, not through these endpoints |

## Runbook: whole fleet

1. **Announce and freeze.** Pick a quiet window. There is no downtime, but a failed run should not coincide with a deployment or a register handover.
2. **Back up.** Take a backup of the wallet storage (`FILESYSTEM` directory or the Postgres keystore store; see [Backups](../maintenance/backups.md)). Keep it: if the rotation goes wrong the old wrapped DEKs are in it.
3. **Prepare the provider.** Create or activate the new KEK version in the provider. Keep the **old version enabled** until step 7.
4. **Run it.** `POST /api/v1/admin/wallets/rotate-kek-all` with step-up and the second approver. Compare `rotatedCount` with the number of software wallets you expect; the rest are HSM, KMS or legacy wallets.
5. **Handle a failed run.** A wallet that cannot be re-wrapped aborts the call with an error naming the keystore; the wallets before it were already re-wrapped. Fix the cause (provider permissions, unreachable KMS) and call again, which re-wraps all of them again.
6. **Verify.** Sign something with a software wallet on a testnet (or run any registry action that signs); watch the audit log for one `WalletKekRotatedEvent` per re-wrapped wallet.
7. **Leave the old version enabled** (see the warning above). If you must retire it, first plan how the TOTP, webhook and secure-link secrets will be re-created (re-enrol operator TOTP, re-issue webhook secrets), and keep a restorable backup of the provider key.

## Runbook: one wallet

Run `POST /api/v1/admin/wallets/{id}/rotate-kek` with step-up, check `rotated: true`, and verify with a signature as in step 6.

## Limits

- The re-wrap is not atomic across wallets; the fleet call is a loop.
- It does not change the wallet master key or the passwords of exported keystores, and does not re-wrap the TOTP, webhook or secure-link secrets (see the warning above).
- Whether re-wrapping suffices for your policy, and how often to rotate, is for your key-management policy to decide; nothing runs on a schedule.
