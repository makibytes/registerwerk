---
title: ERC-20 — Fungible Token
description: ERC-20 standard implementation for equity, utility, and simple fungible security tokens.
---

# ERC-20 — Fungible Token

ERC-20 is the foundational fungible token standard for EVM chains. Every unit is identical and interchangeable. Registerwerk deploys ERC-20 tokens for equity instruments, simple debt instruments, and utility tokens where KYC-gating at the contract level is not required (compliance is enforced at the registry layer instead).

---

## When to use ERC-20

- **Equity tokens** — shares in an unlisted company where transfer restrictions are managed off-chain
- **Simple bonds** — when the issuer does not need on-chain transfer restriction enforcement
- **Utility tokens** — for platform-internal credits or incentive tokens
- **Test issuances** — ERC-20 is the simplest deployment path for new issuers learning the platform

For regulated securities requiring on-chain KYC-gating, consider [ERC-3643](erc3643.md). For bonds with multiple tranches, consider [ERC-3525](erc3525.md).

---

## Registerwerk ERC-20 extensions

Registerwerk deploys a custom `EwpgERC20` contract that extends the standard ERC-20 with:

| Extension | Purpose |
|---|---|
| `mintWithCap` | Respects the `MintControlRule.maxSupply` configured by the operator |
| `pause` / `unpause` | Emergency circuit breaker for the registry operator |
| `freeze(address)` | Registry-layer freeze (maps to `HolderBlock` in DB) |
| `setIsin(string)` | Stores the ISIN on-chain for cross-referencing |
| `setRegistryRef(string)` | Stores the Registerwerk asset ID for audit purposes |

---

## Deployment flow

1. Operator selects `TokenStandard.ERC20` when creating an `Asset`
2. After KYC approval and (optionally) step-up authentication, calls `POST /api/v1/assets/{id}/deploy`
3. `Erc20DeploymentService` constructs and broadcasts the deployment transaction
4. On receipt confirmation, `AssetDeployment` is created with `contractAddress` and `deploymentTxHash`
5. `Asset.status` transitions to `ISSUED`

---

## On-chain admin operations

| Operation | Endpoint | Requires |
|---|---|---|
| Mint tokens | `POST /api/v1/assets/{id}/mint` | REGISTRY_ADMIN + step-up (if supply cap managed) |
| Burn tokens | `POST /api/v1/assets/{id}/burn` | REGISTRY_ADMIN + step-up + 4-eyes |
| Force-transfer | `POST /api/v1/assets/{id}/force-transfer` | REGISTRY_ADMIN + step-up + 4-eyes |
| Freeze address | `POST /api/v1/assets/{id}/freeze/{address}` | REGISTRY_ADMIN + active HolderBlock |
| Pause contract | `POST /api/v1/assets/{id}/pause` | REGISTRY_ADMIN + step-up |

---

## Confidential variant

`CONF_ERC20` deploys a [Zama fhEVM](confidential.md) confidential variant on Fhenix or Inco networks, where balances and transfer amounts are encrypted using Fully Homomorphic Encryption. Use this when the issuer requires privacy of investor positions.
