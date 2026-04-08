---
id: overview
title: Auditor Overview
sidebar_label: Overview
---

# Auditor Overview

The **Auditor** role provides comprehensive read-only access to all registry data. Auditors can examine any issuance, transfer history, investor record, and audit log in the system without being able to modify any data.

## Who uses the auditor role

- **External auditors** — accounting firms and audit companies contracted to review issuer records
- **Regulatory inspectors** — supervisory authorities exercising oversight under eWpG or MiCAR
- **Internal compliance teams** — the issuer organization's own compliance officers
- **Registry operator staff** — operator employees who need read access without administrative privileges

## What auditors can see

| Data category | Access level |
|---------------|-------------|
| All issuances (all issuers) | Full read access |
| Issuance details (ISIN, supply, chain, contract address) | Full read access |
| Transfer history (all tokens, all wallets) | Full read access |
| Investor registry (names, wallet addresses, KYC status) | Full read access |
| Audit log (all system events) | Full read access |
| KYC documents and files | Read access (as configured by operator) |
| Token contract source code and ABI | Full read access |

## What auditors cannot do

- Create, modify, or delete any registry record
- Submit KYC approvals or rejections
- Initiate or approve issuances
- Trigger on-chain transactions
- Modify user accounts or roles

## Audit session logging

All auditor sessions are themselves logged in the audit trail. Every page viewed, search performed, and document opened is recorded with the auditor's identity and timestamp. This meta-audit trail is available to registry operator administrators.

## Getting started

- See [Audit Log](./audit-log) for how to search and filter system events
- See [Token History](./token-history) for how to trace the complete transfer history of any token
