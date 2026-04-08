---
id: faq
title: Frequently Asked Questions
sidebar_label: FAQ
---

# Frequently Asked Questions

## General

### What is the eWpG Registry?

The eWpG Registry is a blockchain-based digital securities registry compliant with the German Electronic Securities Act (Elektronisches Wertpapiergesetz — eWpG). It allows issuers to create, deploy, and manage tokenized securities that are legally recognized under German law.

### Is the registry regulated?

Yes. The registry operates under the eWpG framework. The registry operator holds the required regulatory authorizations to operate a securities register (Wertpapierregister) under German law.

### Can I self-register?

No. Onboarding is operator-initiated. Contact the registry operator to request onboarding. This ensures that all participants are verified before accessing the platform.

---

## Issuers

### How long does the approval process take?

Typically 1–3 business days for initial review. Additional time may be required if the operator requests supplementary documentation.

### Can I change the token parameters after approval?

No. Once an issuance is in the APPROVED state, all parameters (name, ISIN, chain, token standard, total supply) are locked. You can withdraw the submission and return to DRAFT to make changes.

### What does "onchain level" mean?

It determines how much of your compliance logic lives on the blockchain:
- **None** — registry record only, no smart contract deployed
- **Simple** — standard token contract deployed, no compliance enforcement
- **Control** — ERC-3643 contract deployed with on-chain compliance modules

### Can I deploy to multiple chains?

Currently, each issuance is deployed to one network. To issue the same security on multiple chains, you would create separate issuances with the same ISIN. Contact the registry operator if you need multi-chain support.

### What happens to my token if the registry goes offline?

Once a token is deployed to the blockchain, it exists independently of the registry. Investors can always verify their balances and transact on-chain. The registry is an indexing and administration layer; the blockchain is the authoritative source.

---

## Investors

### Do I need a special wallet to hold security tokens?

For ERC-20 tokens, any standard EVM wallet (MetaMask, Ledger, etc.) works. For ERC-3643 tokens, any EVM wallet that supports ERC-20 also works — the compliance logic is in the contract, not the wallet. For Confidential ERC-3643 tokens, you need an FHE-compatible wallet on the Fhenix or Inco network.

### Why can't I receive tokens at my wallet address?

The most common reasons are:
1. Your wallet has not been whitelisted by the issuer
2. Your KYC/AML claims have expired — check **Profile → Identity**
3. Your country is restricted by a compliance module on that token
4. The token is currently suspended

### How do I get KYC approved?

The registry operator manages the KYC process. You will be guided through document submission during onboarding. If your KYC is pending or has expired, navigate to **Profile → Identity → Renew KYC**.

### Are my token holdings public?

For standard ERC-20, ERC-721, ERC-1155, and ERC-3643 tokens: yes, your balance is visible on the public blockchain to anyone with your wallet address. For Confidential ERC-3643 tokens: no, your balance is encrypted on-chain.

---

## Auditors

### Can auditors initiate any transactions?

No. The auditor role is strictly read-only. No auditor action can modify any registry record or trigger any on-chain transaction.

### How do I verify that the registry data matches the blockchain?

Every transfer record in the registry includes the on-chain transaction hash. You can independently verify any transfer on the relevant block explorer using that hash. See [Token History](./auditors/token-history) for details.

### Can I export audit data for my own systems?

Yes. The audit log and token history views support CSV and JSON exports. For large date ranges, exports are generated asynchronously and sent to your email.

---

## Technical

### Which blockchains are supported?

Ethereum, Polygon, Base, Solana, Fhenix, and Inco. Testnets (Sepolia, Amoy, Base Sepolia, Solana Devnet) are also available for testing.

### What token standards are supported?

ERC-20, ERC-721, ERC-1155, ERC-3643, and Confidential ERC-3643. See [Choosing a Token Standard](./issuers/token-standards) for guidance.

### How do I access the API?

The REST API is available at `https://api.registerwerk.example.com`. Documentation is at `/swagger-ui.html`. You need a JWT token from your identity provider to authenticate. See [Authentication](./authentication).
