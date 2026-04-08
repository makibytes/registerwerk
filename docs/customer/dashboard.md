---
id: dashboard
title: Dashboard
sidebar_label: Dashboard
---

# Dashboard

The dashboard is the first screen you see after logging in. It provides a real-time overview of your activity in the registry, tailored to your role.

## Summary cards

At the top of the dashboard you will find summary cards. The cards shown depend on your role:

### Issuer dashboard

| Card | Description |
|------|-------------|
| **Active Issuances** | Number of tokens currently in ISSUED state |
| **Pending Approval** | Issuances waiting for operator review |
| **Total Investors** | Unique investor wallets across all your tokens |
| **Networks** | Distinct blockchain networks where you have deployed tokens |

### Investor dashboard

| Card | Description |
|------|-------------|
| **Token Holdings** | Number of distinct security tokens you hold |
| **Connected Wallets** | Wallets registered with your account |
| **Recent Transfers** | Transfers in the last 30 days |

### Auditor dashboard

| Card | Description |
|------|-------------|
| **Total Issuances** | All issuances in the registry |
| **Transfers (30d)** | Total on-chain transfer events in the last 30 days |
| **Active Issuers** | Number of issuer entities with at least one active token |
| **Pending KYC Reviews** | KYC submissions awaiting operator review (read-only) |

## Recent activity feed

Below the summary cards, the **Recent Activity** panel shows the latest events relevant to your account. Each entry includes:

- **Timestamp** — when the event occurred (your local timezone)
- **Event type** — e.g., *Issuance Created*, *Transfer*, *KYC Approved*
- **Subject** — the token or entity involved
- **Network** — the blockchain network (with chain icon)

Click any activity row to navigate directly to the relevant detail page.

## Quick actions

The **Quick Actions** panel provides one-click navigation to the most common tasks for your role:

- **Issuer**: New Issuance, Manage Investors, View Pending Approvals
- **Investor**: View Holdings, Connect Wallet, Download Statement
- **Auditor**: Open Audit Log, Search Transfers, Export Report

## Network status

The bottom of the dashboard shows a live **Network Status** grid, indicating whether each configured blockchain network is currently reachable and synced. A green indicator means the indexer is current; yellow indicates the indexer is more than 10 blocks behind the chain head; red means the indexer is unavailable.

:::tip
If a network shows red, on-chain data for that network may be stale. Wait a few minutes and refresh. If the issue persists, contact the registry operator.
:::

## Refreshing data

Dashboard data refreshes automatically every 30 seconds. You can force an immediate refresh using the **Refresh** button in the top-right corner of each panel.
