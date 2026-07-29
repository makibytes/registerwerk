---
id: faq
title: Frequently Asked Questions
sidebar_label: FAQ
---

# Frequently Asked Questions

## General

### What is the eWpG Registry?

Registerwerk is a reference implementation for creating and administering electronic-securities records and related blockchain tokens. Whether an instrument is legally recognized under the German Electronic Securities Act (Elektronisches Wertpapiergesetz — eWpG) depends on the instrument, register model, operator, and deployment and must be reviewed externally.

### Is the registry regulated?

Authorisation is deployment- and operator-specific. This repository contains no evidence that a particular operator holds a required regulatory authorisation. Verify the intended activities, operator permissions, and instrument structure with qualified counsel and the relevant operator before use.

### Can I self-register?

No. Onboarding is operator-initiated. Contact the registry operator to request onboarding. This ensures that all participants are verified before accessing the platform.

---

## Issuers

### How long does the approval process take?

Review time is operator- and case-specific. This repository does not define or guarantee a 1–3 business-day service level; ask the responsible operator for the applicable process and timing.

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

Once a token is deployed, the contract may continue to exist independently of this application, subject to the selected network and contract controls. Registerwerk stores an operational holder record and projects or reconciles selected state on-chain. Which record has legal authority is instrument-, register-model-, and jurisdiction-specific and requires an approved perimeter decision; an indexed or on-chain balance alone is not proof of legal title or legal effect.

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
