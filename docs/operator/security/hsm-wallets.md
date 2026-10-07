---
title: HSM-backed wallets
---

# HSM-backed wallets

Registerwerk signs EVM transactions through an `EvmSigner` boundary. Software keystores and
PKCS#11 devices implement the same interface; transaction services never receive a private key.
An HSM wallet stores only its checksummed address and PKCS#11 object label in PostgreSQL. Raw and
keystore export are disabled for these wallets.

## Demo: SoftHSM

`docker compose up` starts SoftHSM, imports Anvil's documented first development key as the
non-exportable `registerwerk-operator` object, and enrolls it as the default EVM operator wallet.
The HSM challenge signature is verified against
`0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` before enrollment. The key and default PINs are
public fixtures and must never leave this disposable environment.

SoftHSM and the unprivileged backend share the token directory through fixed group ID `2000`.
The token files are group read/write and inaccessible to other container users.

## Production: Thales, Utimaco, or another PKCS#11 device

Install the vendor client and mount a SunPKCS11 configuration into the backend container. Then
set:

```dotenv
REGISTERWERK_HSM_ENABLED=true
REGISTERWERK_HSM_PROFILE=THALES
REGISTERWERK_HSM_PROVIDER_CONFIG=/etc/registerwerk/pkcs11.cfg
REGISTERWERK_HSM_PIN=<secret reference injected at runtime>
REGISTERWERK_HSM_SIGNATURE_ALGORITHM=
```

The profile selects the vendor adapter; the SunPKCS11 file selects its native library, slot and
token. A Registerwerk instance supports one HSM configuration. High availability, clustering and
key replication remain responsibilities of the HSM appliance/client.

After the token key exists, use **Operator Portal → Wallets → Attach HSM key**. Enter its object
label and public EVM address. Registerwerk signs an enrollment digest and refuses the wallet if
recovery does not yield that address.

For production readiness:

- use a secp256k1 key with raw-digest ECDSA support;
- make the key non-extractable and restrict it to signing;
- inject the PIN from the platform secret manager, never an image or committed `.env`;
- transfer wallet/default management to dual-control operator accounts;
- test backup, restore and key ceremonies on the vendor's staging appliance;
- monitor signing failures and audit every wallet/default change.

Changing vendor only changes the profile, mounted provider configuration and client library. No
blockchain service or contract deployment code changes.

## Cloud KMS signing (EVM)

Instead of a PKCS#11 device, EVM signing keys can live in a cloud KMS. Set `REGISTERWERK_WALLET_SIGNER=kms` with `registerwerk.wallet.kms.provider=gcp` and the key version (`REGISTERWERK_WALLET_KMS_KEY_VERSION`, a full Cloud KMS key-version resource name). The first adapter is **GCP Cloud KMS** with an `EC_SIGN_SECP256K1_SHA256` key at the HSM protection level; the application authenticates with Application Default Credentials (Workload Identity on GKE, role `roles/cloudkms.signerVerifier` on the key version). Keys are non-exportable and the signer is stateless, so any replica count works.

- Enrol a wallet with `POST /api/v1/admin/wallets/attach-kms` (step-up and a second approver, reason `WALLET_ATTACH_KMS`). The address is derived from the KMS public key (a supplied address must match) and one challenge signature proves that the backend can sign with the key. The wallet can then become a chain default like an HSM wallet. Raw export and keystore export are refused for KMS and PKCS#11 wallets, and KEK rotation skips them (there is no software key to re-wrap).
- In production mode the start-up check refuses `signer=kms` when the provider or key version is missing or malformed, or when the key does not answer within the health-check timeout; it never falls back to a software keystore. A KMS signer satisfies the production HSM/KMS requirement (a PKCS#11 token that is also enabled keeps its own rules).
- Metrics: `registerwerk_kms_sign_total`, `registerwerk_kms_sign_failures_total{reason}`, `registerwerk_kms_sign_retries_total`, `registerwerk_kms_sign_seconds` and the gauge `registerwerk_wallet_kms_signer_ready` (1 ready, 0 failing, -1 off).
- Rotation means attaching the new key version as a new wallet and switching the chain default; the public key of a key version is cached per process and never changes.
- Limits: only EVM keys are covered (Solana, Starknet, Stellar and Canton keys still use the software keystore). The adapter has been exercised against a mocked KMS client only, not a real Cloud KMS project: create the key, grant the role, boot (the readiness check logs the derived address), attach it and sign a testnet transaction before relying on it. The step-by-step setup is in [Cloud-KMS signer setup](kms-signer.md).

## Key-encryption-key (KEK) rotation

Software wallets keep their private key encrypted under a per-wallet data key (DEK), which is wrapped by the platform KEK held by the configured provider (`REGISTERWERK_WALLET_KEK_PROVIDER`: `AWS_KMS`, `AZURE_KEY_VAULT` or `GCP_KMS`; `ENV_VAR` is refused in production mode). Rotating the KEK re-wraps the DEKs under the currently active KEK version; it does not change any key material.

| Endpoint | Scope | Control |
|---|---|---|
| `POST /api/v1/admin/wallets/{id}/rotate-kek` | One wallet | Step-up (`WALLET_KEK_ROTATION`) |
| `POST /api/v1/admin/wallets/rotate-kek-all` | Every wallet | Step-up and a second approver (`WALLET_KEK_ROTATION_ALL`) |

Use the single-wallet call after a suspected exposure of one wallet's storage and the fleet call after a suspected KEK compromise or a planned provider key rotation. The response says whether a DEK was re-wrapped (`rotated`), or lists the re-wrapped wallets (`rotatedWalletIds`, `rotatedCount`). Wallets backed by PKCS#11 or KMS keys, and legacy keystores without a wrapped DEK, are skipped (`rotated: false`). Both calls are audited (`WalletKekRotatedEvent`); neither requires an `Idempotency-Key`. The [KEK rotation runbook](kek-rotation.md) has the procedure and what the endpoints do not cover.
