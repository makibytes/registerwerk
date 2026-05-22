---
title: Confidential Tokens (Zama fhEVM)
description: Privacy-preserving ERC-20 and ERC-3643 tokens using Zama's Fully Homomorphic Encryption.
---

# Confidential Tokens (Zama fhEVM)

Confidential tokens use **Fully Homomorphic Encryption (FHE)** to shield token balances and transfer amounts from public view while preserving the compliance and audit capabilities required by regulators. Registerwerk deploys confidential tokens on **Fhenix** and **Inco** — EVM-compatible networks powered by Zama's fhEVM.

---

## The privacy problem in securities tokenisation

Standard EVM tokens are fully transparent: anyone with a blockchain explorer can see all balances and transfers. For institutional securities, this creates two problems:

1. **Investor privacy** — position sizes reveal trading strategy and portfolio composition
2. **Market integrity** — visible large positions can be front-run or copied

Confidential tokens solve this by encrypting balances and transfer amounts using FHE. The encrypted values can be computed on (addition, comparison) without decryption, so the compliance module can still enforce transfer limits and KYC requirements on encrypted data.

---

## Supported confidential standards

| Standard | Chain | Based on |
|---|---|---|
| `CONF_ERC20` | Fhenix / Inco | ERC-20 with FHE-encrypted balances |
| `CONF_ERC3643` | Fhenix / Inco | ERC-3643 with FHE-encrypted balances + identity |

---

## How Zama fhEVM works

In Zama fhEVM:

1. **Ciphertext on-chain**: Instead of storing `balance: uint256`, the contract stores `balance: euint256` — an encrypted 256-bit integer
2. **FHE operations**: The contract can perform `add`, `sub`, `gt`, `lt` etc. on encrypted values without decrypting them
3. **Decryption gateway**: Only authorised parties (the token holder, and via a permission system the operator/auditor) can decrypt their own balance using the **Zama Gateway**
4. **Operator audit capability**: The contract's `auditDecrypt(address, uint64 until)` function grants temporary view access to the operator — enabling regulatory audit without permanent exposure

---

## Compliance on encrypted values

[ERC-3643](erc3643.md) compliance modules can operate on encrypted balances through FHE-aware compliance contracts. For example:

- **Transfer limit module**: compares the encrypted transfer amount against the encrypted daily limit using `fhevm.lt(transferAmount, dailyLimit)` — the comparison is computed on encrypted values
- **Balance verification**: after a transfer, verifies that the sender's encrypted balance is `≥ 0`

This means KYC-gating and compliance rules work identically to plaintext ERC-3643, but the amounts are never revealed to third parties.

---

## Operator audit access

Regulators and auditors can request time-limited decryption access. The operator calls:

```
POST /api/v1/assets/{id}/grant-audit-access
Body: { "holderEntityId": "...", "accessUntil": "2026-12-31", "reason": "..." }
```

This calls `ConfidentialKeyManager.grantAuditAccess()`, which:
1. Signs a decryption permission with the operator's authority key
2. Submits the permission to the Zama Gateway
3. Records the grant in the audit log with the requesting authority's identity and justification

The audit grant is time-limited and automatically revoked at `accessUntil`.

---

## Networks

| Network | Token enum | Status | Chain ID |
|---|---|---|---|
| Fhenix mainnet | `CONF_ERC20`, `CONF_ERC3643` | Production | 21888 |
| Fhenix Helium testnet | Same | Testnet | 8008135 |
| Inco Gentry mainnet | Same | Production | 9090 |
| Inco Rivest testnet | Same | Testnet | 21097 |

See [Confidential EVM](../blockchains/confidential-evm.md) for network configuration.
