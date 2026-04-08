---
id: managing-investors
title: Managing Investors
sidebar_label: Managing Investors
---

# Managing Investors

This guide explains how to add investors to your issuance, whitelist their wallets, and manage their ONCHAINID for ERC-3643 tokens.

## Adding an investor

Investors must first be registered in the eWpG Registry as entities. If your investor is not yet in the system, contact the registry operator to onboard them.

Once an investor entity exists in the registry:

1. Navigate to your issuance and click **Investors → Add Investor**
2. Search for the investor by name, email, or entity ID
3. Select the investor and click **Add**

The investor is now linked to your issuance in the registry database. For **Simple** tokens (ERC-20/721/1155), this is sufficient — you can transfer tokens to their wallet directly.

For **Control** tokens (ERC-3643), you must also whitelist the investor's wallet (see below).

## Whitelisting wallets (ERC-3643)

ERC-3643 tokens enforce that only whitelisted, KYC-verified investors can receive tokens. The whitelist is stored in the **Identity Registry** contract on-chain.

### Step 1 — Investor provides wallet address

The investor connects their wallet in the customer portal under **Wallets → Connect Wallet** (see [Wallet Setup](../investors/wallet-setup)) and shares the wallet address with you.

### Step 2 — Verify the investor has an ONCHAINID

Every ERC-3643 investor must have an **ONCHAINID** — a smart contract that serves as their on-chain identity. The registry creates one automatically when the investor entity is onboarded.

You can check this under **Investor → [name] → ONCHAINID**. The ONCHAINID contract address is displayed if it exists.

### Step 3 — Check KYC/AML claims

ERC-3643 tokens require investors to hold valid **claims** on their ONCHAINID — cryptographic attestations issued by a trusted KYC provider. Your issuance requires at minimum:

- **Claim topic 1**: KYC (Know Your Customer)
- **Claim topic 2**: AML (Anti-Money Laundering)

The registry operator issues these claims after the investor completes the KYC review process. You can see the claim status on the investor detail page.

:::warning
You cannot whitelist an investor whose ONCHAINID does not have valid KYC/AML claims. Attempting to do so will be rejected by the on-chain identity registry.
:::

### Step 4 — Register the wallet in the Identity Registry

Once the investor has a valid ONCHAINID and claims:

1. Navigate to your issuance → **Investors → [investor name]**
2. Click **Add Wallet**
3. Enter the wallet address provided by the investor
4. Click **Register on Chain**

The registry backend submits a transaction to the Identity Registry contract, linking the wallet address to the investor's ONCHAINID. This typically takes 5–15 seconds.

Once registered, the wallet is whitelisted. The investor can now receive tokens at that address.

## Removing an investor

To remove an investor's wallet from the whitelist:

1. Navigate to **Investors → [investor name] → Wallets**
2. Click **Remove from Whitelist** next to the wallet address
3. Confirm the action

The registry submits a transaction removing the wallet from the Identity Registry. The investor will no longer be able to receive tokens, and any future transfer to that wallet will be automatically rejected by the smart contract.

:::note
Removing an investor from the whitelist does not confiscate their existing token balance. If you need to recover tokens (e.g., due to a court order), contact the registry operator — this requires a forced transfer operation performed by the token agent.
:::

## Compliance modules

For ERC-3643 tokens, the operator configures compliance modules that automatically enforce additional rules:

| Module | Description |
|--------|-------------|
| **MaxBalance** | Limits the maximum token balance any single investor may hold |
| **MaxInvestors** | Caps the total number of distinct investors |
| **CountryRestrict** | Blocks investors from specified jurisdictions |

These modules run automatically on every transfer attempt. If a transfer would violate a module rule, it is rejected on-chain without any action required from you.

Contact the registry operator if you need to adjust module parameters for your issuance.
