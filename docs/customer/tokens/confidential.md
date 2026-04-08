---
id: confidential
title: Confidential ERC-3643
sidebar_label: Confidential Tokens
---

# Confidential ERC-3643

Confidential ERC-3643 extends the standard T-REX security token with **Fully Homomorphic Encryption (FHE)**, provided by Zama's fhEVM technology. Investor balances and transfer amounts are encrypted on-chain — they are not visible to the public, while compliance enforcement and auditability are fully preserved.

## What is Fully Homomorphic Encryption?

In normal computing, you must decrypt data before you can work with it. Fully Homomorphic Encryption (FHE) is a special form of encryption that allows computation to be performed directly on encrypted data — without ever decrypting it.

In plain terms: the smart contract can check whether a transfer is compliant, add or subtract balances, and enforce all compliance rules — all while the actual numbers remain hidden behind encryption. Even the blockchain nodes processing the transaction cannot see the values.

## Why confidentiality matters for institutional securities

Public blockchains are, by design, transparent. This is great for auditability but creates problems for institutional investors:

- **Market impact**: Large position sizes, if visible, can move prices against the holder
- **Competitive sensitivity**: Institutional strategies depend on keeping position sizes private
- **Regulatory requirements**: Some jurisdictions require that investor holdings not be disclosed to other market participants

Confidential ERC-3643 solves this by making balances and transfer amounts visible only to:
- The investor themselves (via their wallet)
- The issuer (via a designated decryption key)
- The registry operator (for compliance and audit purposes)
- Any auditor explicitly granted access

## How it works

When you receive or transfer a Confidential ERC-3643 token:

1. The token amount is encrypted using your public FHE key before the transaction is submitted
2. The smart contract processes the encrypted transfer, updating encrypted balance states
3. The blockchain records only the encrypted values — no one can read the actual amounts from the transaction data
4. To view your balance, you use your private key to request decryption from the Zama Key Management System (KMS) Gateway — this is a gas-free operation

The compliance check (KYC, AML, country restrictions) still happens on-chain, but the compliance modules operate on encrypted inputs. The result of the compliance check (pass/fail) is visible, but the specific amount is not.

## Supported chains

Confidential ERC-3643 is currently only available on:

| Network | Status |
|---------|--------|
| **Fhenix Mainnet** | Production |
| **Inco Mainnet** | Production |
| Fhenix Testnet | Available for testing |
| Inco Testnet | Available for testing |

These are purpose-built FHE-enabled EVM chains. Standard Ethereum, Polygon, and Base do not support FHE computation natively.

## Viewing your encrypted balance

In the portal:

1. Navigate to **Investments → Holdings**
2. Find a Confidential ERC-3643 token (shown with a lock icon)
3. Click **Decrypt Balance**
4. Your wallet prompts you to sign a decryption authorization message
5. The portal contacts the KMS Gateway, which validates your authorization and returns the decrypted value
6. Your balance is displayed for this session only — it is not stored anywhere

## Current limitations

- FHE computation is significantly more expensive in gas than standard EVM operations — expect 10–50x higher gas costs per transfer
- Wallet tooling for FHE chains is maturing; not all standard wallets are supported
- Cross-chain bridges for FHE tokens are not yet available
- Batch transfers and some compliance modules are not yet supported in the confidential variant

## Roadmap

The Zama fhEVM ecosystem is evolving rapidly. Planned improvements include:
- Support for additional EVM-compatible chains as FHE co-processors become available
- Reduced gas costs as FHE hardware accelerators become mainstream
- Standardized wallet integrations
- Cross-chain confidential token bridges

:::tip
Use confidential tokens for pilot institutional issuances where privacy is critical, but evaluate the maturity of the tooling carefully before committing large volumes.
:::
