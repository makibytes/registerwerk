---
title: Choosing a Token Standard
---

# Choosing a Token Standard

The eWpG Registry supports five token standards. This page helps you understand the differences and select the right one for your issuance.

## ERC-20 — Fungible Token

ERC-20 is the most widely supported token standard on Ethereum-compatible chains. All tokens of the same class are identical and interchangeable.

**Pros**
- Supported by virtually every wallet, exchange, and DeFi protocol
- Simple to deploy and manage
- Low gas cost for transfers

**Cons**
- No built-in compliance enforcement — anyone can receive the token
- No native support for partial amounts in fractional securities

**Best for**: Fungible securities where compliance is managed entirely off-chain, or internal test deployments.

---

## ERC-721 — Non-Fungible Token (NFT)

ERC-721 tokens are unique — each token has a distinct ID and owner. This makes them suitable for securities that represent a unique asset or specific unit.

**Pros**
- Each token is individually identifiable (useful for debentures with unique terms)
- Rich metadata support via `tokenURI`
- Strong wallet and marketplace support

**Cons**
- Not suitable for large numbers of fungible units (one transaction per token)
- Higher gas cost per transfer compared to ERC-20

**Best for**: Unique securities, individual bonds, or structured products where each unit has distinct terms.

---

## ERC-1155 — Multi-Token Standard

ERC-1155 allows a single contract to manage multiple token types — both fungible and non-fungible — simultaneously.

**Pros**
- Efficient batch operations: transfer multiple token types in one transaction
- Can represent both fungible and non-fungible securities in one contract
- Lower gas cost for batch operations compared to multiple ERC-20/721 contracts

**Cons**
- Less widely supported by retail wallets than ERC-20 or ERC-721
- No built-in compliance enforcement

**Best for**: Issuers managing multiple tranches or series of securities who want to reduce contract complexity.

---

## ERC-3643 (T-REX) — Recommended for Regulated Securities

ERC-3643, also known as T-REX (Token for Regulated EXchanges), is an open standard specifically designed for regulated security tokens. It is the **recommended standard** for most issuances under eWpG.

**Pros**
- On-chain compliance: transfers are blocked automatically if either party fails compliance checks
- Investor identity is verified via ONCHAINID, a decentralized identity standard
- Granular compliance modules (max balance, max investors, country restrictions, etc.)
- Segregation of agent roles (identity agents, transfer agents, compliance agents)
- Fully compatible with DeFi protocols that support ERC-20 interface

**Cons**
- More complex initial setup (requires deploying multiple contracts)
- Investors must have an ONCHAINID and valid KYC/AML claims before receiving tokens
- Slightly higher gas cost per transfer due to compliance checks

**Best for**: Any regulated security issuance where transfer restrictions must be enforced automatically on-chain.

See the full deep-dive at [ERC-3643 explained](../../token-standards/erc3643.md).

---

## Confidential ERC-3643 — Privacy-Preserving Regulated Tokens

Confidential ERC-3643 extends the T-REX standard with Fully Homomorphic Encryption (FHE), provided by Zama's fhEVM. Token balances and transfer amounts are encrypted on-chain — only authorized parties can decrypt them.

**Pros**
- Investor balances are hidden from public view while remaining auditable by authorized parties
- Full compliance enforcement is preserved (the smart contract can verify compliance on encrypted data)
- Suitable for institutional use cases where position sizes must remain confidential

**Cons**
- Only available on Fhenix and Inco networks
- Higher gas cost due to FHE computation
- Limited wallet and tooling support compared to standard ERC-3643
- Investors need FHE-compatible wallet tooling to interact

**Best for**: Institutional securities where confidentiality of holdings is a regulatory or commercial requirement.

See [Confidential Tokens explained](../../token-standards/confidential.md).

---

## Decision guide

```
Is on-chain compliance enforcement required?
  YES → Are balances required to be confidential?
            YES → Confidential ERC-3643
            NO  → ERC-3643 (T-REX)
  NO  → Are tokens unique/non-fungible?
            YES → ERC-721
            NO  → Do you need multiple token types in one contract?
                      YES → ERC-1155
                      NO  → ERC-20
```
