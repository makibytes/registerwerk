---
title: Confidential EVM (Fhenix / Inco)
description: Zama fhEVM-powered confidential EVM chains — Fhenix and Inco — for privacy-preserving securities.
---

# Confidential EVM (Fhenix / Inco)

Fhenix and Inco are EVM-compatible networks that integrate Zama's **fhEVM** (Fully Homomorphic Encryption EVM). They allow smart contracts to perform computations on encrypted data — balances are never visible in plaintext on-chain, yet compliance rules can still be enforced.

---

## Supported networks

| Chain | Chain enum | Network | Chain ID |
|---|---|---|---|
| Fhenix | `FHENIX` | MAINNET | 21888 |
| Fhenix Helium | `FHENIX` | TESTNET | 8008135 |
| Inco Gentry | `INCO` | MAINNET | 9090 |
| Inco Rivest | `INCO` | TESTNET | 21097 |

Both Fhenix and Inco support the same `CONF_ERC20` and `CONF_ERC3643` contract interfaces.

---

## How FHE protects privacy

On a standard EVM chain, `balanceOf(address)` returns a plaintext `uint256`. On fhEVM:

- `balanceOf(address)` returns an `euint256` — a ciphertext
- Only the address owner (and permitted auditors) can decrypt this value using their private key + the Zama Gateway
- All arithmetic operations on balances (add, subtract, compare) happen in encrypted form — the EVM never sees the plaintext

From a regulatory perspective:
- The **issuer and registry operator** always have audit access (via time-limited decryption permissions)
- The **investor** can always decrypt their own balance
- **Third parties** (other investors, market participants) cannot see balances or transfer amounts

---

## Zama Gateway

The Zama Gateway is an off-chain service that mediates decryption requests. For each confidential operation:

1. The smart contract emits an encrypted log (ciphertext)
2. The authorised party submits a decryption request to the Gateway with their access key
3. The Gateway verifies the permission, decrypts, and returns the plaintext to the requester

Registerwerk's `ConfidentialKeyManager` wraps Gateway API calls. It manages:
- The operator's master decryption key (stored in KMS alongside wallet keys)
- Per-entity, time-limited decryption grants (for regulatory audits)
- Key rotation schedules

---

## ElGamal key for ConfidentialTransfer (SPL-2022 crossover)

Note: Solana's SPL-2022 `ConfidentialTransfer` extension uses **ElGamal** encryption, not Zama FHE. Do not confuse the two:

| System | Encryption | Used by |
|---|---|---|
| Zama fhEVM | FHE (TFHE scheme) | `CONF_ERC20`, `CONF_ERC3643` on Fhenix/Inco |
| SPL-2022 ConfidentialTransfer | ElGamal (twisted Edwards) | `SPL_2022_CONFIDENTIAL` on Solana |

---

## Use case: confidential bond position

A Luxembourg fund issuer wants to offer an ERC-3643 regulated token but cannot reveal investor position sizes to other market participants.

1. Deploy `CONF_ERC3643` on Fhenix mainnet
2. Investors are onboarded through the standard KYC flow — [ERC-3643 identity claims](../token-standards/erc3643.md) still apply
3. The compliance module enforces KYC-gating using FHE comparisons
4. The CSSF regulatory audit team is granted time-limited decryption access for examination
5. Other investors cannot see how many tokens any other investor holds

This satisfies both the CSSF's investor identity requirements and institutional clients' privacy expectations.
