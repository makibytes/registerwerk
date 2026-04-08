---
id: audit-log
title: Audit Log
sidebar_label: Audit Log
---

# Audit Log

The audit log records every significant event in the eWpG Registry — from user logins to on-chain deployments. It is the authoritative record of all actions taken in the system.

## Accessing the audit log

Navigate to **Audit → Audit Log**. The log opens with the most recent events at the top.

## Event types

The audit log captures events in several categories:

### Authentication events

| Event | Description |
|-------|-------------|
| `USER_LOGIN` | User successfully authenticated |
| `USER_LOGOUT` | User explicitly logged out |
| `USER_SESSION_EXPIRED` | Session timed out |
| `FAILED_LOGIN` | Authentication attempt failed |

### Issuance events

| Event | Description |
|-------|-------------|
| `ISSUANCE_CREATED` | A new issuance was created (DRAFT) |
| `ISSUANCE_SUBMITTED` | Issuer submitted for approval |
| `ISSUANCE_APPROVED` | Operator approved the issuance |
| `ISSUANCE_REJECTED` | Operator rejected the issuance |
| `ISSUANCE_DEPLOYED` | Token deployed to blockchain |
| `ISSUANCE_SUSPENDED` | Issuer suspended the token |
| `ISSUANCE_REDEEMED` | Issuer marked the token as redeemed |

### On-chain events (indexed from blockchain)

| Event | Description |
|-------|-------------|
| `TRANSFER` | Token transfer between wallets |
| `IDENTITY_REGISTERED` | Investor wallet registered in Identity Registry |
| `IDENTITY_REMOVED` | Investor wallet removed from Identity Registry |
| `CLAIM_ADDED` | KYC/AML claim added to ONCHAINID |
| `CLAIM_REMOVED` | Claim removed from ONCHAINID |
| `TOKEN_PAUSED` | Token contract paused (suspension) |
| `TOKEN_UNPAUSED` | Token contract unpaused |

### KYC events

| Event | Description |
|-------|-------------|
| `KYC_SUBMITTED` | Investor submitted KYC documents |
| `KYC_APPROVED` | Operator approved KYC |
| `KYC_REJECTED` | Operator rejected KYC |
| `KYC_EXPIRED` | KYC claims expired and require renewal |

## Searching and filtering

Use the filter panel to narrow down the log:

- **Date range** — specify start and end dates/times (UTC)
- **Event type** — select one or more event types from the list above
- **Entity** — filter by issuer, investor, or auditor organization
- **User** — filter events performed by a specific user
- **Token / ISIN** — filter to events related to a specific issuance
- **Network** — filter on-chain events by blockchain network

### Example: Find all transfers for a specific ISIN

1. Set **Event type** to `TRANSFER`
2. Enter the ISIN in the **Token / ISIN** field
3. Set your desired date range
4. Click **Apply**

## Audit log entry detail

Click any log entry to view full details:

- **Event ID** — unique identifier for the event
- **Timestamp** — precise UTC timestamp
- **User / System** — who or what triggered the event
- **IP address** — source IP (for user-initiated events)
- **Parameters** — the full event payload (JSON)
- **Transaction hash** — for on-chain events, the blockchain transaction
- **Previous state / New state** — for state transitions

## Exporting the audit log

Click **Export** to download the current filtered view:

- **CSV** — all fields in tabular format
- **JSON** — full event payload for each record

Exports are generated asynchronously for large date ranges. You will receive an email with a download link when the export is ready.

:::tip
For regulatory submissions, use the **PDF** export option available on the issuance detail page. It generates a formatted audit report for a single issuance, including all lifecycle events and on-chain evidence.
:::
