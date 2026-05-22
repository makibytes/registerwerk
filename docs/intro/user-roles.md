---
title: User Roles & Permissions
description: Who uses Registerwerk, what they can do, and which regulatory obligation each role addresses.
---

# User Roles & Permissions

Registerwerk is multi-tenant: one operator deployment serves many customer legal entities. Access is controlled by a role set defined in the `AppRole` enum and enforced via `@PreAuthorize` on every controller method.

---

## Role overview

| Role | Portal | Who holds it | Regulatory obligation |
|---|---|---|---|
| `REGISTRY_ADMIN` | Operator | Registry staff | eWpG §15 registry keeper; GwG §10 AML officer |
| `COMPLIANCE_OFFICER` | Operator | Compliance / AML team | GwG §7 compliance officer; AMLD6 Art. 8 |
| `AUDITOR` | Operator | Internal/external auditors | eWpG §15(3) record access |
| `ISSUER` | Customer | Securities issuers | eWpG §4 issuer obligations |
| `INVESTOR` | Customer | Token holders / investors | |
| `COMPANY_ADMIN` | Customer | Issuer's admin users | |
| `TRADER` | Customer | Execution access for trading venue integrations | MiFIR Art. 26 reporting |
| `SECOND_APPROVER` | Operator | Dual-control approver for [4-eyes operations](../compliance/step-up-mfa.md) | GwG §6(2) internal controls |

---

## Operator roles

### REGISTRY_ADMIN

The highest-privilege role. A `REGISTRY_ADMIN` can:

- Create, update, and deactivate [Legal Entities](../intro/concepts.md#customer-entities)
- Approve and reject [KYC documents](../compliance/kyc-aml.md)
- Deploy and manage [security tokens](../token-standards/index.md)
- Issue [Sperrvermerk](../compliance/sperrvermerk.md) (trading restrictions) — requires [step-up authentication](../compliance/step-up-mfa.md)
- Force-transfer and force-burn tokens — requires step-up + 4-eyes
- Impersonate customer users for support purposes — requires step-up
- Access all [audit log](../platform/audit-log.md) records
- Trigger [MiFIR](../compliance/mifir.md) and [DAC8](../compliance/dac8.md) regulatory exports

!!! warning "Force operations require dual control"
    Force-transfer, force-burn, and force-approve are irreversible on-chain operations. They require both a step-up token (TOTP or WebAuthn) **and** a confirmation from a second `SECOND_APPROVER`. This implements the Vier-Augen-Prinzip required by GwG §6(2) for high-risk operations.

### COMPLIANCE_OFFICER

Focused on AML/KYC functions:

- Review and manage [sanctions screening](../compliance/sanctions-screening.md) runs and hits
- Accept or reject screening hits (with dual-control for high-risk entities)
- Approve KYC documents for their assigned jurisdictions
- Issue and lift [Sperrvermerk](../compliance/sperrvermerk.md) — requires step-up
- Access [DORA](../compliance/dora.md) incident records
- Trigger on-demand sanctions re-screening

### AUDITOR

Read-only access to the full audit trail:

- Read all [audit log](../platform/audit-log.md) entries
- Verify the audit hash chain integrity
- Export audit records for external review
- Access screening run history and KYC document versions

### SECOND_APPROVER

A special role activated for the [4-eyes principle](../compliance/step-up-mfa.md). Users with this role can confirm dual-control operations initiated by a `REGISTRY_ADMIN`. The second approver must be a different user from the initiator.

---

## Customer roles

Customer users access the platform through the customer frontend (:4201) via Kong. Their JWT contains the `X-Entity-Id` claim identifying which `LegalEntity` they belong to, enforcing data isolation.

### ISSUER

An issuer can:

- Create and manage their own [asset](../token-standards/index.md) definitions
- Initiate token deployment (subject to operator approval if required)
- Manage investor onboarding for their tokens
- View [corporate action](../intro/concepts.md) history for their securities
- Download position statements and regulatory documents

### INVESTOR

An investor can:

- View their portfolio (tokens held, positions)
- Accept transfer requests
- View transaction history
- Download their position statements

### COMPANY_ADMIN

Manages users and roles within a customer legal entity:

- Invite and remove company users
- Assign `ISSUER` / `INVESTOR` / `TRADER` roles within their entity
- View entity KYC status (but cannot approve it — only operators can)

### TRADER

A machine or human user authorised to interact with trading venue integrations:

- Submit and manage trade listings
- View trade execution reports
- These actions are reported to regulators via [MiFIR RTS 22](../compliance/mifir.md)

---

## Impersonation

`REGISTRY_ADMIN` users can impersonate a customer user to investigate issues or assist with onboarding. Impersonation:

- Requires [step-up authentication](../compliance/step-up-mfa.md)
- Is recorded in the [audit log](../platform/audit-log.md) immediately on start and end
- Is visible to all `REGISTRY_ADMIN` users via the impersonation bar in the customer frontend
- Automatically expires after a configurable timeout
