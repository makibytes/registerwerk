---
id: creating-issuance
title: Creating an Issuance
sidebar_label: Creating an Issuance
---

# Creating an Issuance

This guide walks you through the complete process of creating a new security token issuance, from filling in the asset details to deploying it on-chain.

## Prerequisites

- Your organization has completed onboarding and KYC review
- You have the **Issuer** or **Company Admin** role
- You have the ISIN or WKN for the security (or have obtained one)

## Step 1 — Open the New Issuance form

Navigate to **Issuances → New Issuance** in the top navigation. The creation wizard opens with five sections.

## Step 2 — Fill in asset details

| Field | Required | Description |
|-------|----------|-------------|
| **Name** | Yes | Human-readable name of the security (e.g., "Example AG Bond 2025") |
| **ISIN** | Yes | International Securities Identification Number |
| **WKN** | No | German securities identifier (Wertpapierkennnummer) |
| **Asset type** | Yes | Bond, Share, Fund unit, Structured product |
| **Currency** | Yes | Denomination currency (ISO 4217, e.g., EUR) |
| **Total supply** | Yes | Total number of tokens to be issued |
| **Nominal value** | No | Value per token in the chosen currency |
| **Prospectus URL** | No | Link to the legally approved prospectus document |
| **Description** | No | Free-text description visible in the registry |

:::tip
The **Name** and **ISIN** fields are permanently recorded in the on-chain token metadata and cannot be changed after deployment. Take care to enter them correctly.
:::

## Step 3 — Select the onchain level

Choose how much of the compliance logic should live on-chain:

- **None** — Registry-only, no blockchain deployment. Suitable for testing or purely administrative records.
- **Simple** — Deploy an ERC-20, ERC-721, or ERC-1155 token. Transfers are unrestricted on-chain; compliance is managed off-chain.
- **Control** — Deploy an ERC-3643 (T-REX) token. Transfers are enforced on-chain by compliance modules. Requires additional setup (see [Managing Investors](./managing-investors)).

For most regulated issuances under eWpG, select **Control**.

## Step 4 — Select chain and network

Choose the target blockchain network:

| Network | Environment | Notes |
|---------|-------------|-------|
| Ethereum Mainnet | Production | Highest security, highest gas cost |
| Ethereum Sepolia | Testnet | For testing only |
| Polygon Mainnet | Production | Lower gas cost, fast finality |
| Base Mainnet | Production | Coinbase L2, low cost |
| Solana Mainnet | Production | High throughput, SPL tokens |
| Fhenix / Inco | Production | Confidential tokens only |

:::warning
You cannot migrate a token to a different chain after deployment. Choose carefully. Use a testnet first to validate your configuration.
:::

## Step 5 — Select token standard

The available token standards depend on the onchain level selected:

| Onchain level | Available standards |
|---------------|---------------------|
| None | — (no token deployed) |
| Simple | ERC-20, ERC-721, ERC-1155 |
| Control | ERC-3643, Confidential ERC-3643 |

For guidance on which standard to choose, see [Token Standards](./token-standards).

## Step 6 — Review and submit

Review the summary of your configuration. If everything looks correct, click **Submit for Approval**.

Your issuance enters the **PENDING_APPROVAL** state. The registry operator will review your submission within 1–3 business days. You will receive an email notification when the review is complete.

:::note
The operator may request additional documentation (e.g., a signed prospectus or legal opinion) before approving. Respond to any requests promptly to avoid delays.
:::

## Step 7 — Deploy once approved

When you receive an approval notification, navigate to **Issuances** and find your issuance in the **APPROVED** state. Click **Deploy to Blockchain** to initiate the on-chain deployment.

See [Deploying to Chain](./deploying-to-chain) for full deployment instructions.

## What happens next

After deployment, your issuance moves to the **ISSUED** state. You can then:

1. [Add investors and whitelist wallets](./managing-investors)
2. Monitor transfers on the [Dashboard](../dashboard)
3. Manage the token [lifecycle](./lifecycle)
