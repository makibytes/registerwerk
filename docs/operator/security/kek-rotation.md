---
title: KEK rotation runbook
description: When and how to re-wrap wallet, TOTP, webhook and Travel Rule secrets under the current key-encryption key, how to retire an old KEK version safely, and how to check the result.
---

# KEK rotation runbook

Software wallets keep their private key encrypted under a per-wallet **data key** (DEK). The DEK is stored wrapped by the platform **key-encryption key** (KEK) held by the configured provider (`REGISTERWERK_WALLET_KEK_PROVIDER`: `AWS_KMS`, `AZURE_KEY_VAULT` or `GCP_KMS`; `ENV_VAR` is development only and is refused in production mode). Four more kinds of secret are wrapped by the same KEK: operator **TOTP secrets**, **webhook signing secrets** (current and previous), **Travel Rule peer keys**, and the **secure links** (invite and password-reset) that sit in an undelivered event publication. *Rotating the KEK* re-wraps every DEK under the KEK version that is currently active. It changes **no key material**: the wallet keys, TOTP and webhook secrets, their addresses and the contract roles granted to them stay as they are; only the wrapped data key is replaced, and no secret is decrypted or returned by the re-wrap.

Wallets whose key is in a PKCS#11 HSM or in a cloud-KMS signer have no software key and are skipped, and so are legacy keystores that predate envelope encryption (no wrapped DEK).

## When to run it

| Situation | Action |
|---|---|
| Storage of one wallet's keystore may have been exposed (copied volume, restored backup in the wrong place) | Single wallet |
| The KEK is suspected compromised, or a provider key version is being retired | Whole fleet |
| Routine key-version rotation inside the provider | The nightly job moves every secret onto the new version. Run the re-wrap and the retire call when you want the old version disabled |

!!! warning "A KEK compromise is not solved by re-wrapping alone"
    Anyone who copied the wrapped DEKs *and* could use the old KEK version has everything needed to recover the keys. Re-wrapping protects against future copies of the storage. If the KEK itself was compromised, also treat the wallet keys as exposed: generate new wallets, move registry roles and funds to them, and retire the old ones.

## One re-wrap for every secret type, and a guard for old versions

`POST /api/v1/admin/kek/rewrap` (step-up **and a second approver**, reason `KEK_REWRAP`, bound to the request like the other approved actions) moves every ciphertext that is not on the active KEK version onto it, for all five types in one call: wallet keystores (`WALLET_KEY`), `TOTP_SECRET`, `WEBHOOK_SECRET`, `TRAVEL_RULE_PEER_KEY`, and `SECURE_LINK_IN_FLIGHT`. It answers counts only:

```json
{"rewrapped": 7, "failed": 0, "byType": {"TOTP_SECRET": {"rewrapped": 2, "failed": 0}, "WEBHOOK_SECRET": {"rewrapped": 4, "failed": 0}}}
```

- **Idempotent and resumable.** A second call finds nothing left to do (`rewrapped: 0`). Rows are read in pages and each is its own transaction, so an interrupted run loses nothing and the next call carries on. One run at a time across all nodes (a concurrent call answers 409).
- **A failing row does not stop the batch.** It is counted in `failed` and in `registerwerk_kek_rewrap_failures_total{type}`; the log names the row id, never a secret. The usual cause is a ciphertext that no configured KEK version can open any more.
- **Secure links are counted, not rewritten.** They live inside the Spring Modulith event publication until the mail is sent, and editing that record would strand the publication. They drain when the publication completes or the link expires.
- **Audit.** One `KEK_REWRAP_COMPLETED` event with counts per type and the active version, and the second approver's identity.
- **Nightly job.** After a rotation the platform re-wraps by itself at `registerwerk.kek.rewrap.cron` (default `0 30 3 * * *`, one node via ShedLock). It is on by default (`KEK_REWRAP_ENABLED=true`); turn it off to run the endpoint by hand. It writes an audit event only when it did work. `KEK_REWRAP_BATCH_SIZE` (default 100) sets the page size.
- **See where things stand.** `GET /api/v1/admin/kek/status` lists, per secret type, how many ciphertexts are on each KEK version label. The gauge `registerwerk_kek_secrets_on_old_version{type}` is the number that are not on the active version; the `KekSecretsOnOldVersion` alert fires when it stays above zero for 36 hours and `KekRewrapFailures` when a re-wrap failed.

`POST /api/v1/admin/kek/versions/{version}/retire` (step-up and a second approver, reason `KEK_VERSION_RETIRE`) is the guarded way to retire an old version. It answers **409** while any ciphertext of any type still references that version, naming the counts (for example `TOTP_SECRET=2, WEBHOOK_SECRET=1`); it also refuses the active version, and fails closed (409) when an inventory cannot be read. When nothing references the version it is audited (`KEK_VERSION_RETIRED`). Where the provider allows it, the version also stops being used in the running process (the development env-var provider); for cloud KMS the application cannot disable a version, so **disable or destroy it in the provider console only after this call succeeded**.

How a ciphertext is attributed to a version depends on the provider:

| Provider | Version labels |
|---|---|
| `ENV_VAR` (development) | `registerwerk.wallet.master-key-version` (default `v1`) for the active key; retired keys stay readable under `registerwerk.wallet.previous-master-keys.<label>` until retired |
| `GCP_KMS` | `primary` or `previous` (Cloud KMS reports whether the primary version decrypted the value). Retire `previous` after a re-wrap, then disable the old version in the console |
| `AWS_KMS`, `AZURE_KEY_VAULT` | The configured key id (a pinned key or key version), so there is never anything on an "old" version inside one configuration; moving to a different key remains a planned migration |

!!! warning "Disabling a version in the console bypasses the guard"
    The guard only protects the retire call. If someone disables a KEK version directly at the provider, every ciphertext still wrapped under it becomes unreadable. Always run `GET /api/v1/admin/kek/status` (all counts on the active version) and the retire call first.

## What the endpoints do

| Endpoint | Scope | Protection |
|---|---|---|
| `POST /api/v1/admin/wallets/{id}/rotate-kek` | One wallet | Step-up, reason `WALLET_KEK_ROTATION` |
| `POST /api/v1/admin/wallets/rotate-kek-all` | Every wallet | Step-up **and a second approver**, reason `WALLET_KEK_ROTATION_ALL` (see the [approval queue](approval-queue.md)) |

Both are for `REGISTRY_ADMIN`. Neither requires an `Idempotency-Key`; running a rotation again is harmless. The single call answers `{"rotated": true|false}`; the fleet call answers `{"rotatedWalletIds": [...], "rotatedCount": n}`. Each re-wrapped wallet writes a `WalletKekRotatedEvent` to the [audit log](../../platform/audit-log.md).

The endpoints re-wrap **under the key the backend is configured with**. How that behaves depends on the provider:

| Provider | Re-wrap | Consequence |
|---|---|---|
| `GCP_KMS` (`registerwerk.wallet.kms.key-id` is the key, without a version) | Decrypt, then encrypt under the key's primary version | Make a new version primary and re-wrap; disable the old version only after the retire call succeeded |
| `AWS_KMS` | `ReEncrypt` to the configured key id | Enable automatic key rotation in KMS, or re-encrypt to another key by changing `key-id` only if the old key can still decrypt the existing DEKs during the run. Test first |
| `AZURE_KEY_VAULT` (`key-id` includes a key version) | Unwrap and wrap with the same configured key version | The endpoint cannot move DEKs to a different key version: the configured version is the only one used to unwrap. Rotate the Azure key version through a planned migration, not through these endpoints |

## Runbook: whole fleet

1. **Announce and freeze.** Pick a quiet window. There is no downtime, but a failed run should not coincide with a deployment or a register handover.
2. **Back up.** Take a backup of the wallet storage (`FILESYSTEM` directory or the Postgres keystore store; see [Backups](../maintenance/backups.md)). Keep it: if the rotation goes wrong the old wrapped DEKs are in it.
3. **Prepare the provider.** Create or activate the new KEK version in the provider. Keep the **old version enabled** until step 7.
4. **Run it.** `POST /api/v1/admin/wallets/rotate-kek-all` with step-up and the second approver. Compare `rotatedCount` with the number of software wallets you expect; the rest are HSM, KMS or legacy wallets. (`POST /api/v1/admin/kek/rewrap` below does the same for wallets and everything else at once.)
5. **Handle a failed run.** A wallet that cannot be re-wrapped aborts the call with an error naming the keystore; the wallets before it were already re-wrapped. Fix the cause (provider permissions, unreachable KMS) and call again, which re-wraps all of them again.
6. **Verify.** Sign something with a software wallet on a testnet (or run any registry action that signs); watch the audit log for one `WalletKekRotatedEvent` per re-wrapped wallet.
7. **Re-wrap the other secrets.** Wait for the nightly job or call `POST /api/v1/admin/kek/rewrap`; check that `failed` is 0 and that `GET /api/v1/admin/kek/status` shows every count on the active version (only an undelivered secure link may remain for a short while).
8. **Retire the old version.** `POST /api/v1/admin/kek/versions/{version}/retire`; on 409 fix what it names and repeat. Then disable the version in the provider. Keep a restorable backup of the provider key for the retention period.

## Runbook: one wallet

Run `POST /api/v1/admin/wallets/{id}/rotate-kek` with step-up, check `rotated: true`, and verify with a signature as in step 6.

## Limits

- The re-wrap is not atomic across wallets; the fleet call is a loop.
- It does not change the wallet master key or the passwords of exported keystores.
- The re-wrap replaces only the wrapped data key; whether that suffices for your policy, and how often to rotate the KEK itself, is for your key-management policy to decide. The nightly job moves secrets onto the active version after you rotate; it does not rotate the KEK.
- Legacy keystores without a wrapped DEK, HSM and KMS wallets are not in the inventory.
- A webhook secret or TOTP secret that was written in plaintext before the envelope existed is not covered by the re-wrap; the startup backfill encrypts those first.
