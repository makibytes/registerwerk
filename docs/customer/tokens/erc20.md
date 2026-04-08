---
id: erc20
title: ERC-20 Tokens
sidebar_label: ERC-20
---

# ERC-20 Tokens

ERC-20 is the foundational token standard for fungible assets on Ethereum-compatible blockchains. All tokens issued under ERC-20 are identical and interchangeable — one token is worth exactly as much as any other token of the same class.

## What does "fungible" mean?

Fungible means that individual units of the token are not distinguishable from each other. A euro is fungible — one euro is exactly equivalent to any other euro. Similarly, an ERC-20 security token with a balance of 1,000 means you hold 1,000 identical units of that security.

## How ERC-20 works in the registry

When an issuer selects ERC-20 as the token standard:

- A standard ERC-20 contract is deployed on the chosen blockchain
- The total supply is minted to the issuer's wallet at deployment
- The issuer can then distribute tokens to investors

**There are no built-in transfer restrictions.** Any wallet address can receive an ERC-20 token. Compliance with investor eligibility rules must be managed off-chain (e.g., by only transferring to verified investors and monitoring secondary trading separately).

## What this means for investors

If you hold an ERC-20 security token:

- Your balance is a number stored in the token contract
- You can verify your balance on any block explorer (e.g., Etherscan)
- Transfers happen on-chain; the registry monitors and records them
- You do not need an ONCHAINID or KYC claims to hold ERC-20 tokens

## Transparency

ERC-20 token balances are fully public. Anyone with your wallet address can look up your balance on the blockchain. If privacy of holdings is important, consider whether [Confidential ERC-3643](./confidential) might be more appropriate.

## Limitations for regulated securities

ERC-20 alone is generally not sufficient for fully on-chain compliant security tokens under eWpG, because:

- It cannot enforce who is allowed to hold the token
- It has no built-in mechanism for freeze, confiscation, or forced transfer
- There is no link between token holders and verified identities

For regulated securities requiring on-chain compliance, consider [ERC-3643](./erc3643).
