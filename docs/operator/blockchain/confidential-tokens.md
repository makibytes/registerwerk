---
title: Confidential Tokens (Zama fhEVM)
---

# Confidential Token Setup (Zama fhEVM)

This guide covers deploying and administering Confidential ERC-20/ERC-3643 tokens using Zama's
fhEVM.

## Prerequisites

1. A chain with **real Zama fhEVM infrastructure** — Ethereum Sepolia today (documented addresses
   are vendored in `contracts/lib/fhevm/library-solidity/config/`, and bundled in `@zama-fhe/relayer-sdk` as
   `SepoliaConfig`), or Ethereum/Base mainnet once Zama publishes final addresses there.
   Confidential deployment is gated to `Chain.ETHEREUM`/`Chain.BASE` — **not** Fhenix/Inco.
2. `EwpgConfidentialFactory` deployed and configured with that chain's real FHEVM addresses
   (`setFhevmInfra`) — see `docs/blockchains/confidential-evm.md` in the repository.
3. For `CONF_ERC3643` only: a real T-REX `IdentityRegistry` provisioned for confidential assets on
   that chain, configured via `registerwerk.contracts.confidential-identity-registry.<chain>`.
   Deployment fails loudly if this is unset.
4. The operator's and an auditor's dedicated decrypt-only viewer addresses configured via
   `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` — these become viewers on every confidential token
   deployed on that chain from block one.
5. `zama-relayer` running (`docker compose --profile confidential up`) with
   `OPERATOR_DECRYPT_PRIVATE_KEY` set to the private key matching the operator-viewer address
   above, and the backend's `registerwerk.zama.relayer-url` pointed at it.

## Deploying

Standard asset-deployment flow, same as any other standard:

```bash
curl -X POST http://localhost:48080/api/v1/assets/{assetId}/deploy \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -d '{ "chain": "ETHEREUM", "network": "TESTNET" }'
```

The backend routes `CONF_ERC20`/`CONF_ERC3643` to `ConfidentialErc20Service`/
`ConfidentialErc3643Service`, which call `EwpgConfidentialFactory.deployConfidentialErc20`/
`deployConfidentialErc3643` — real Web3j transactions, passing the configured operator/auditor
viewer addresses as `initialViewers`.

## Operator actions available today

| Action | Endpoint | Notes |
|---|---|---|
| Confidential mint (issuer/operator issuance) | `POST /api/v1/assets/{id}/deployments/{depId}/issuer/mint-confidential` | Encrypts the amount server-side via the `zama-relayer` sidecar — no browser/wallet needed. Needs step-up (`ISSUER_MINT_CONFIDENTIAL`) and a second approver bound to the request body, and the destination must be a screened register holder, exactly like the plain mint |
| Confidential forced burn (§26 Einziehung) | `POST .../admin/force-burn-confidential` | Same server-side encrypt path; already agent/owner-gated — that gating IS the forced-burn authority |
| Add a confidential viewer | `POST .../admin/confidential-add-viewer` | Grants decrypt rights on every holder's balance going forward — e.g. adding an auditor or the issuer's own wallet after deployment |
| Remove a confidential viewer | `POST .../admin/confidential-remove-viewer` | Stops future grants — does NOT retroactively revoke already-decryptable historical handles (Zama's ACL has no revoke primitive) |
| Register-vs-on-chain reconciliation | `GET /api/v1/assets/{id}/confidential-reconciliation` | Headless: decrypts every holder's on-chain balance via the backend's own operator-decrypt key and compares it against the register's plaintext `nominalAmount`. `REGISTRY_ADMIN` or `AUDIT` role. |
| Reveal + reconcile via your own wallet | Operator Portal → Asset → **Confidential Balances** tab | Connect a viewer wallet in the browser and decrypt directly against Zama's relayer — an independent cross-check of the headless reconciliation above |
| Public supply disclosure | `ConfidentialERC20.requestSupplyDisclosure()`, then `fulfillSupplyDisclosure(requestId, cleartexts, proof)` with the relayer's `publicDecrypt` result (on-chain calls; no operator API endpoint wraps them yet) | For a regulator-triggered aggregate disclosure, not a specific holder's balance |

Freeze/pause/forced-transfer on `CONF_ERC3643` are **not yet wired** through the operator API —
the existing ERC-3643 admin controller targets the plaintext `EwpgERC3643` contract's ABI, which
does not match `ConfidentialERC3643`'s encrypted-amount signatures.

## The relayer sidecar

`zama-relayer` (repo root `zama-relayer/`) is Registerwerk's own service wrapping the real
`@zama-fhe/relayer-sdk` — built and shipped in this monorepo, not something you need to write.
Zama publishes no Java/JVM client, which is the only reason this sidecar exists; every
browser-initiated confidential action (investor/issuer/auditor revealing a balance, an investor's
confidential transfer) talks to Zama's relayer directly from the browser and never touches this
sidecar. Enable it with:

```bash
docker compose --profile confidential up
```

See its `.env.example` section ("Confidential tokens (Zama fhEVM)") for the environment
variables — `ZAMA_CONFIG_PRESET=sepolia`, `ZAMA_OPERATOR_DECRYPT_PRIVATE_KEY`, and
`REGISTERWERK_ZAMA_RELAYER_URL` on the backend side.

### Relayer production mode and viewer-key revocation

!!! warning "Operator decrypt key handling"
    With `RELAYER_PRODUCTION_MODE=true` (set by default in the Helm chart; off in Compose) the relayer
    refuses to start with the environment-held KEK provider (`ENV_VAR`) or a plaintext
    `OPERATOR_DECRYPT_PRIVATE_KEY`, because that secret would sit in the same environment as the key
    it protects. Use a KMS provider (`RELAYER_KEK_PROVIDER=AWS_KMS|GCP_KMS|AZURE_KEY_VAULT`; these
    are placeholders that report "not configured" until a vendor client is wired, parked decision
    T7-05) or acknowledge the exception explicitly with `RELAYER_ALLOW_ENV_KEK=true` (logged as a
    warning). Production mode also requires `OPERATOR_DECRYPT_DURATION_DAYS` to be set explicitly
    (the demo default is 365 days); a shorter lifetime means more frequent re-authorisation
    (open decision T7-07). The key derivation of the env provider is unchanged on purpose: it stays
    byte-compatible with the backend and with already wrapped values.

If the operator decrypt key may be compromised, revoke it per token:

1. Call `POST .../admin/confidential-remove-viewer` (`removeViewer`) for the viewer address on
   **every** confidential token it was registered on.
2. Generate a new operator-decrypt keypair, register its address with `confidential-add-viewer` on
   each token, and re-wrap/redeploy `OPERATOR_DECRYPT_PRIVATE_KEY(_WRAPPED)`.
3. Remember that removal stops future grants only; balance handles the old key could already decrypt
   stay decryptable (Zama's ACL has no revoke primitive).

## Investor/issuer/auditor balance decryption

Revealing a confidential balance (or encrypting a confidential-transfer amount) is a **client-side**
operation in both frontends: the connected wallet signs an EIP-712 request and the browser's own
`@zama-fhe/relayer-sdk` instance talks to Zama's Relayer directly — see `FheClientService` in
`frontend-customer` (investor self-reveal + confidential transfer; issuer reveal-all-holders) and
`frontend-operator` (operator/auditor `ConfidentialViewerPanelComponent`). None of this routes
through this backend.
