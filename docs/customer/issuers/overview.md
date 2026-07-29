---
id: overview
title: Issuer Overview
sidebar_label: Overview
---

# Issuer Overview

As an issuer, you can use Registerwerk to create, deploy, and administer tokenized-asset records. Legal recognition under the German Electronic Securities Act (eWpG), and the legal effect of any register or token action, depend on the approved instrument and operator perimeter and are not established by this software.

## What issuers can do

- **Create issuances** — define the legal and technical parameters of a security token
- **Select blockchain** — choose the target network (Ethereum, Polygon, Base, Solana, or a confidential chain)
- **Choose token standard** — ERC-20, ERC-721, ERC-1155, ERC-3643, or Confidential ERC-3643
- **Manage investors** — add and remove investors, manage wallet whitelisting, handle ONCHAINID registration for ERC-3643
- **Monitor transfers** — view real-time transfer activity indexed from the blockchain
- **Lifecycle management** — move tokens through states from DRAFT to ISSUED, and eventually to REDEEMED

## Issuance onchain levels

When creating an issuance, you choose one of three *onchain levels*:

| Level | Description | Use case |
|-------|-------------|----------|
| **None** | Token is recorded in the registry database only; no on-chain deployment | Legal record-keeping without blockchain deployment |
| **Simple** | Token is deployed on-chain; full transparency, no compliance logic | Fungible tokens with off-chain compliance |
| **Control** | Token deployed with on-chain compliance (requires ERC-3643); transfers restricted by smart contract | Regulated issuances with automatic enforcement |

## Roles within an issuer organization

An issuer organization can have multiple users with different permissions:

| Role | Permissions |
|------|-------------|
| **Company Admin** | Full access, manage users, configure IdP |
| **Issuer** | Create and manage issuances, manage investors |
| **Viewer** | Read-only access to your organization's data |

## Getting started

1. Ensure your organization has completed [onboarding](../onboarding) and KYC review
2. [Create your first issuance](./creating-issuance)
3. Once approved, [deploy to the blockchain](./deploying-to-chain)
4. [Add investors](./managing-investors) and whitelist their wallets
