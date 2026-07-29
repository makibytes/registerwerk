---
title: Confidential Tokens (Zama fhEVM)
description: Privacy-preserving ERC-20 and ERC-3643 tokens using Zama's Fully Homomorphic Encryption — the complete encrypt/decrypt lifecycle, end to end.
---

# Confidential Tokens (Zama fhEVM)

Confidential tokens use **Fully Homomorphic Encryption (FHE)** to shield token balances and
transfer amounts from public view while preserving the compliance and audit capabilities
required by regulators.

:::caution Registerwerk IS the client
Earlier revisions of this page described the encrypt/decrypt lifecycle as "someone else's
problem" — the browser's job, a companion service you must supply yourself. That was the wrong
framing: the only parties allowed to decrypt confidential balances — issuers, investors, the
registry operator, and an auditor — all act *through* Registerwerk. Building the full
`@zama-fhe/relayer-sdk` integration is therefore Registerwerk's own responsibility, and it is now
built: contracts, an in-repo relayer sidecar, backend services, and browser integration in both
frontends. See the [status matrix](#status) below for exactly what's real vs. what still needs a
live network to exercise.
:::

---

## Supported confidential standards

| Standard | Based on | Encrypted state |
|---|---|---|
| `CONF_ERC20` | ERC-7984 confidential fungible token | `euint64` balances/allowances |
| `CONF_ERC3643` | ERC-3643 (T-REX) + ERC-7984 | `euint64` balances + plaintext identity/compliance |

Contracts: `contracts/src/confidential/ConfidentialERC20.sol` / `ConfidentialERC3643.sol`,
deployed via `contracts/src/factory/EwpgConfidentialFactory.sol`.

---

## Which chains actually run this

Zama's fhEVM coprocessor runs on **Ethereum and Base** (per Zama's own "fhEVM Coprocessor" product
announcement) — plus **Sepolia today** as the fully-configured testnet (real ACL/Executor/
Payment/KMSVerifier/Gateway addresses are vendored in `contracts/lib/fhevm/config/`, and the same
real Sepolia addresses are bundled in `@zama-fhe/relayer-sdk` as `SepoliaConfig`). Zama's own
Ethereum **mainnet** addresses were still being finalised at the time of writing (targeted Q3
2026) and are governance-upgradeable even once live.

**Fhenix and Inco are NOT Zama fhEVM chains.** They run their own, separate, incompatible FHE
stacks. `ConfidentialERC20`/`ConfidentialERC3643` are built specifically against Zama's
`TFHE.sol`/Gateway API and will not function on either.

**T-REX Chain**: T-REX Network announced in March 2026 that Zama is becoming the confidentiality
layer for the T-REX Ledger — directly relevant to `CONF_ERC3643`, which already combines T-REX
identity/compliance with Zama's FHE balances. T-REX Chain is not yet represented as its own
`Chain` enum value in this backend, and has not (yet, publicly) shared its own FHEVM
infrastructure addresses. Confirm those before relying on this pairing in production.

FHEVM infrastructure addresses are never hardcoded per network in the contracts — they're
injected at construction/factory-configuration time (`ConfidentialERC20.FhevmInfra`,
`EwpgConfidentialFactory.setFhevmInfra`) precisely so a new network (mainnet, T-REX Chain) can be
targeted by configuring real addresses, not by a contract redeploy.

---

## Who can decrypt what — the viewer ACL model

Zama's ACL grants are additive and per-ciphertext-handle: once an address is `allow`ed on a
handle, that grant is permanent for that specific handle (there is no revoke — see
`ConfidentialERC20.removeViewer`'s doc comment). Registerwerk uses that primitive to give exactly
the isolation the platform requires, within a **single confidential contract per asset** (not one
contract per investor — see below):

- **Each holder is granted decrypt rights on their OWN balance handle only**, every time it
  mutates (mint/transfer/burn). An investor can never decrypt another investor's balance, because
  they are never `allow`ed on that other handle.
- **A small "viewer" set** — the registry operator, an auditor, and (added post-deploy via
  `addViewer`) the issuer's own wallet if desired — is granted decrypt rights on **every** handle
  (balance and total supply), satisfying "the operator needs to decrypt all amounts of all
  investors and the auditor role needs to be able to decrypt amounts."
- Viewers are provisioned as `initialViewers` at deploy time (`EwpgConfidentialFactory.
  deployConfidentialErc20/deployConfidentialErc3643`, sourced from
  `registerwerk.contracts.confidential-operator-viewer.*` / `.confidential-auditor-viewer.*`) or
  added/removed later via `TokenAdminService.confidentialAddViewer`/`confidentialRemoveViewer`
  (`POST .../admin/confidential-add-viewer` / `-remove-viewer`).

Why one contract with an ACL, not one contract per investor: identical isolation guarantee, at
normal deployment/gas cost, with no per-investor supply-reconciliation complexity.

---

## What the contracts actually do

- `confidentialTransfer` / `confidentialTransferFrom` / `confidentialApprove` — ERC-7984 encrypted
  transfer/allowance, with `TFHE.select`-based silent-failure semantics on insufficient balance
  (matches ERC-7984 convention, not a bug).
- `confidentialMint` / `confidentialBurn` — owner/agent-gated, granting the viewer set (above) on
  every mutated handle. On `ConfidentialERC3643`, `confidentialBurn` is also the
  compulsory-cancellation primitive (eWpG §26 Einziehung) for encrypted amounts.
- `ConfidentialERC3643` additionally enforces T-REX identity verification, freeze, pause, and a
  pluggable `IConfidentialCompliance` module before any transfer.
- `requestSupplyDisclosure` / `callbackSupplyDisclosure` — the **public/oracle decryption** path:
  the contract itself asks Zama's Gateway to decrypt the total supply and receives the cleartext
  back via a signed callback, for a regulator-triggered disclosure — distinct from a holder/viewer
  decrypting their own or another's balance via the Relayer (below).

---

## The encrypt/decrypt lifecycle — who does what {#status}

| Actor | Action | How | Status |
|---|---|---|---|
| Investor | Reveal own balance | Browser: `FheClientService.userDecrypt` (connected wallet signs the KMS EIP-712 request, decrypts directly against Zama's relayer) | ✅ Real — `frontend-customer` |
| Investor | Confidential transfer | Browser: `FheClientService.encrypt64` client-side, then the wallet submits `confidentialTransfer` | ✅ Real — `frontend-customer` |
| Issuer | Confidential mint | Backend encrypts server-side (no browser in this flow) via `zama-relayer` sidecar, then submits | ✅ Real — `TokenAdminService.confidentialMint`, `POST .../issuer/mint-confidential` |
| Issuer | Reveal any holder's balance | Browser, as a registered viewer (same `FheClientService.userDecrypt` path) | ✅ Real — `frontend-customer`'s issuer confidential-balances panel |
| Operator | Headless decrypt for reports/reconciliation | Backend's dedicated operator-decrypt key via `zama-relayer`, no wallet | ✅ Real — `ConfidentialBalanceReconciliationService`, `GET .../confidential-reconciliation` |
| Operator / Auditor | Reveal + reconcile via own wallet | Browser: `frontend-operator`'s Confidential Balances tab (`ConfidentialViewerPanelComponent`) | ✅ Real |
| Operator | Confidential force-burn (§26 Einziehung) | Backend encrypts server-side via `zama-relayer`, then submits | ✅ Real — `TokenAdminService.confidentialForceBurn`, `POST .../force-burn-confidential` |
| Regulator | Public/oracle total-supply disclosure | On-chain: `requestSupplyDisclosure`/`callbackSupplyDisclosure` | ✅ Real, Foundry-tested |
| Confidential ERC-3643 freeze/pause/forced-transfer via the operator API | — | `Erc3643Controller` targets the plaintext `EwpgERC3643` ABI; calling it against `ConfidentialERC3643` sends mismatched calldata | ❌ Not wired — only forced-burn has a confidential-specific path today |
| Confidential payment rail (encrypted stablecoin amounts in the DvP cash leg) | — | — | ❌ Not built |

**What's genuinely unverified here**: this sandbox has no live Docker/Kong and no funded Sepolia
account to submit real transactions, so the on-chain submit → mine → decrypt round trip has not
been executed end-to-end in this environment. What **has** been verified against Zama's real,
live Sepolia infrastructure during development: `zama-relayer`'s `/v1/encrypt-input` endpoint
produced a genuine ciphertext handle and ZK input proof from a live `createInstance` connection to
Zama's real relayer (`https://relayer.testnet.zama.org`) and a public Sepolia RPC — not a mock.
Every component here is built, unit/Foundry-tested, and (where checked) live-network-verified at
the individual-call level; only the full multi-step transaction round trip needs a funded account
and a deployed asset to complete.

---

## Deploying a confidential asset

1. Deploy `EwpgConfidentialFactory` on a chain with real Zama FHEVM addresses configured (Sepolia
   today), or configure an existing factory via `setFhevmInfra`.
2. For `CONF_ERC3643`, provision a shared T-REX `IdentityRegistry` for confidential assets on that
   chain and set `registerwerk.contracts.confidential-identity-registry.<chain>` — deploying with
   an unconfigured/zero identity registry fails loudly (`EwpgConfidentialFactory` reverts).
3. Set `registerwerk.contracts.confidential-factory.<chain>` to the deployed factory address, and
   `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` to the operator's/auditor's dedicated decrypt-only
   viewer addresses (see [Confidential EVM](../blockchains/confidential-evm.md)).
4. Deploy `zama-relayer` (`docker compose --profile confidential up`) with
   `OPERATOR_DECRYPT_PRIVATE_KEY` set to the private key matching the operator-viewer address
   above, and point the backend at it via `registerwerk.zama.relayer-url`.
5. Issue the asset as `CONF_ERC20`/`CONF_ERC3643` — deployment is gated to real Zama-coprocessor
   chains (`Chain.ETHEREUM`, `Chain.BASE`), not Fhenix/Inco.

See [Confidential EVM](../blockchains/confidential-evm.md) for chain configuration detail and
[Operator: Confidential Tokens](../operator/blockchain/confidential-tokens.md) for the day-to-day
operator workflow.
