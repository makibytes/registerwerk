---
title: Cloud-KMS signer setup (GCP)
description: Create a secp256k1 signing key in Google Cloud KMS, grant access, configure the backend, attach it as a wallet and verify it before relying on it.
---

# Cloud-KMS signer setup (GCP)

With the cloud-KMS signer, an EVM signing key lives in Google Cloud KMS and never in the Registerwerk process: the backend sends a digest to KMS and receives a signature. The key cannot be exported, the signer is stateless, so any number of backend replicas can use it, and a KMS signer counts as the production signing-custody target (alternatively a [PKCS#11 HSM](hsm-wallets.md)). The overview and metrics are in [HSM-backed wallets](hsm-wallets.md#cloud-kms-signing-evm); this page is the procedure.

!!! warning "Not exercised against a real Cloud KMS project"
    The adapter is covered by tests that use a mocked KMS client. It has not been run against a live Cloud KMS project. Do the verification at the end of this page, including a testnet transaction, before using it for anything that matters.

Only **GCP** is implemented (`provider: gcp`); the provider interface is there for others. Only **EVM** keys are covered: Solana, Starknet, Stellar and Canton keys still use the software keystore.

## 1. Create the key

Create an asymmetric signing key with algorithm `EC_SIGN_SECP256K1_SHA256` (the curve Ethereum uses) at the HSM protection level, which is what the defaults and the Helm values assume:

```bash
gcloud kms keyrings create registerwerk --location europe-west3
gcloud kms keys create registry-signer \
  --keyring registerwerk --location europe-west3 \
  --purpose asymmetric-signing \
  --default-algorithm ec-sign-secp256k1-sha256 \
  --protection-level hsm
```

The backend refuses a key with any other algorithm. A key *version* is what you configure and attach:

```
projects/<project>/locations/<location>/keyRings/<ring>/cryptoKeys/<key>/cryptoKeyVersions/<n>
```

Rotating to a new key version produces a **different Ethereum address**: it is a new wallet, not a rotation of the old one.

## 2. Grant access

The backend authenticates with Application Default Credentials and no secret is configured in Registerwerk. Give its identity the role `roles/cloudkms.signerVerifier` (permissions `cloudkms.cryptoKeyVersions.useToSign` and `viewPublicKey`) on the key:

```bash
gcloud kms keys add-iam-policy-binding registry-signer \
  --keyring registerwerk --location europe-west3 \
  --member "serviceAccount:<gsa>@<project>.iam.gserviceaccount.com" \
  --role roles/cloudkms.signerVerifier
```

On GKE use Workload Identity: annotate the Kubernetes service account with `iam.gke.io/gcp-service-account=<gsa>@<project>.iam.gserviceaccount.com` and set `serviceAccount.automountServiceAccountToken=true` in the Helm values. Outside GKE, mount a service-account key file and set `GOOGLE_APPLICATION_CREDENTIALS`; prefer workload identity because a key file is itself a secret that can sign.

## 3. Configure the backend

| Variable (Helm value) | Default | Purpose |
|---|---|---|
| `REGISTERWERK_WALLET_SIGNER` (`wallets.kmsSigner.enabled`) | blank | `kms` enables the signer |
| `REGISTERWERK_WALLET_KMS_PROVIDER` (`provider`) | `gcp` | Only `gcp` is implemented |
| `REGISTERWERK_WALLET_KMS_KEY_VERSION` (`keyVersion`) | blank | The registry signer key version, probed at start-up. More key versions can be attached as wallets at runtime |
| `REGISTERWERK_WALLET_KMS_TIMEOUT` (`timeout`) | `5s` | Per KMS call |
| `REGISTERWERK_WALLET_KMS_MAX_ATTEMPTS` (`maxAttempts`) | `3` | Bounded retries; only `UNAVAILABLE`, `ABORTED` and timeouts are retried |
| `REGISTERWERK_WALLET_KMS_RETRY_BACKOFF` | `200ms` | Delay between retries |
| `REGISTERWERK_WALLET_KMS_HEALTH_CHECK_TIMEOUT` (`healthCheckTimeout`) | `10s` | Start-up reachability probe |

In production mode the start-up check **stops the application** if the provider is unsupported, the key version is missing or malformed, or the key does not answer a public-key request within the health-check timeout (this also proves the credentials and the algorithm). Outside production mode it logs a warning. It never falls back to a software keystore. The log line shows the Ethereum address derived from the public key; keep it for the next step. Gauge `registerwerk_wallet_kms_signer_ready` is `1` when ready, `0` when enabled but failing and `-1` when off.

## 4. Attach the key as a wallet

Operator portal: **Wallets → Attach KMS key**, or `POST /api/v1/admin/wallets/attach-kms`:

```json
{ "name": "registry-kms-1",
  "keyVersion": "projects/.../cryptoKeys/registry-signer/cryptoKeyVersions/1",
  "address": "0x..." }
```

`address` is optional; if you supply it, it must equal the address derived from the key. The call needs **step-up and a second approver** (reason `WALLET_ATTACH_KMS`, see the [approval queue](approval-queue.md)). The backend derives the address from the KMS public key and has the key sign one enrolment challenge, which proves that this deployment can actually sign with it. Only the key reference is stored. The wallet then appears with custody `KMS` and can be made a chain default like any other wallet, with the usual four-eyes rule. Raw export and keystore export are refused for KMS wallets, and [KEK rotation](kek-rotation.md) skips them (there is no software key to re-wrap).

Fund the address with gas on each chain you use.

## 5. Verify before relying on it

1. The start-up log shows the derived address and no readiness error; `registerwerk_wallet_kms_signer_ready` is `1`.
2. Attach the wallet and make it the default for a **testnet** chain.
3. Send a harmless registry transaction on the testnet (for example register an identity) and confirm it is mined from the expected address.
4. Watch `registerwerk_kms_sign_total`, `registerwerk_kms_sign_failures_total{reason}`, `registerwerk_kms_sign_retries_total` and `registerwerk_kms_sign_seconds`. If Data Access audit logs are enabled for Cloud KMS, they show every signature request.
5. Only then make the KMS wallet the default for production chains and grant the contract roles (registry, claim issuer) to its address.

## Failure behaviour

If KMS is unreachable, signing fails after the bounded retries (logged and counted in the metrics above); nothing is signed locally as a fallback. Treat the KMS key's availability as part of the register's availability and alert on `registerwerk_kms_sign_failures_total`.
