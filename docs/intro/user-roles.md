---
title: User Roles & Permissions
description: Who uses Registerwerk, what they can do, and which regulatory obligation each role addresses.
---

# User Roles & Permissions

Registerwerk is multi-tenant: one operator deployment serves many customer legal entities. Access is controlled by a role set defined in the `AppUserRole` enum and enforced via `@PreAuthorize` on every controller method.

---

## Role overview

| Role | Portal | Who holds it | Regulatory obligation |
|---|---|---|---|
| `REGISTRY_ADMIN` | Operator | Registry staff | eWpG §15 registry keeper; GwG §10 AML officer |
| `COMPLIANCE_OFFICER` | Operator | Compliance / AML team | GwG §7 compliance officer; AMLD6 Art. 8 |
| `AUDIT` | Operator | Internal/external auditors | eWpG §15(3) record access |
| `SUPPORT_AGENT` | Operator | Support staff | Read-only customer sessions only; no regulatory function |
| `ISSUER` | Customer | Securities issuers | eWpG §4 issuer obligations |
| `INVESTOR` | Customer | Token holders / investors | |
| `COMPANY_ADMIN` | Customer | Issuer's admin users | |
| `TRADER` | Customer | Execution access for trading venue integrations | MiFIR Art. 26 reporting |

---

## Operator roles

### REGISTRY_ADMIN

The highest-privilege role. A `REGISTRY_ADMIN` can:

- Create, update, and deactivate [Legal Entities](../intro/concepts.md#customer-entities)
- Approve and reject [KYC documents](../compliance/kyc-aml.md)
- Deploy and manage [security tokens](../token-standards/index.md)
- Issue [Sperrvermerk](../compliance/sperrvermerk.md) (trading restrictions) — requires [step-up authentication](../compliance/step-up-mfa.md)
- Force-transfer and force-burn tokens — requires step-up + 4-eyes
- Start read-only impersonation sessions for support purposes (step-up and a recorded reason; write sessions exist in demo mode only), see [Impersonation](#impersonation)
- Access all [audit log](../platform/audit-log.md) records
- Trigger [MiFIR](../compliance/mifir.md) and [DAC8](../compliance/dac8.md) regulatory exports

!!! warning "Force operations require dual control"
    Force-transfer, force-burn, and force-approve are irreversible on-chain operations. The current implementation requires a second, distinct operator (a `REGISTRY_ADMIN` or `COMPLIANCE_OFFICER`) to provide the dual-control approval; there is no `SECOND_APPROVER` application role. Its legal and policy adequacy requires external review.

### COMPLIANCE_OFFICER

Focused on AML/KYC functions:

- Review and manage [sanctions screening](../compliance/sanctions-screening.md) runs and hits
- Accept or reject screening hits (always with step-up and a second approver)
- Approve KYC documents for their assigned jurisdictions
- View [Sperrvermerk](../compliance/sperrvermerk.md) records (creating and lifting them is `REGISTRY_ADMIN` only, with step-up and a second approver)
- Access [DORA](../compliance/dora.md) incident records
- Trigger on-demand sanctions re-screening

### AUDIT

Read-only access to the full audit trail:

- Read all [audit log](../platform/audit-log.md) entries
- Verify the audit hash chain integrity
- Export audit records for external review
- Access screening run history and KYC document versions

### Dual-control approver

Dual-control approval is currently a capability of a second, distinct user who holds `REGISTRY_ADMIN` or `COMPLIANCE_OFFICER`, not a separate application role. The approver must be different from the initiator, must still be enabled in the database, and must satisfy the configured step-up checks. Requests can be filed and approved in the in-app approval queue (see [Step-up MFA and 4-eyes](../compliance/step-up-mfa.md)).

### SUPPORT_AGENT

Operator staff for customer support. A `SUPPORT_AGENT` can list customer entities and start **read-only** [impersonation](#impersonation) sessions (step-up and a reason required). It cannot change anything and has no regulatory function. Granting or removing the role needs step-up and a second approver.

---

## Customer roles

Customer users access the platform through the customer frontend (`:44201`), whose API calls pass through Kong. Their JWT carries an `entityId` claim (also emitted as `entity_id`) identifying which `LegalEntity` they belong to, and the backend enforces data isolation from it on every request.

`X-Entity-Id` is a *header* name, not a claim — and one Kong deliberately **strips** from inbound requests so it cannot be forged. Nothing in the backend trusts it.

### ISSUER

An issuer can:

- Create and manage their own [asset](../token-standards/index.md) definitions
- Initiate token deployment (subject to operator approval if required)
- Manage investor onboarding for their tokens
- Propose [corporate actions](../intro/concepts.md) — dividends, splits, early calls — for operator review, and withdraw a proposal before it's reviewed
- Attest that a corporate action's settlement is ready — the first of the two required parties, alongside an operator's confirmation
- View corporate action history for their securities
- Download position statements and regulatory documents

### INVESTOR

An investor can:

- View their portfolio (tokens held, positions)
- Accept transfer requests
- View transaction history
- View corporate actions affecting their holdings and download settlement confirmations
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
- The platform keeps an order and execution record (export for the operator at `/api/v1/admin/trading/order-history`); it does **not** file MiFIR RTS 22 reports — see [MiFIR](../compliance/mifir.md) (draft, unvalidated)

---

## Impersonation

Impersonation lets operator staff open the customer portal inside a customer's organisation to investigate issues. It is gated and read-only by default:

- Starting a session needs [step-up authentication](../compliance/step-up-mfa.md) and a mandatory written reason (at least 15 characters, plus an optional ticket reference)
- The default mode is **read-only**; the write mode (`ACT_ON_BEHALF`) needs a second approver and is available **only in demo mode**. In production mode every session is read-only
- `REGISTRY_ADMIN` and `SUPPORT_AGENT` can start read-only sessions; only `REGISTRY_ADMIN` can start a write session. `SUPPORT_AGENT` can do nothing else
- The start call returns no token: a one-time code (60 seconds) is exchanged for an httpOnly session cookie. The session lasts at most 30 minutes
- The token's `sub` remains the **operator's** user id, so every action is attributed to the operator and never to the customer; `imp` marks it in the [audit log](../platform/audit-log.md)
- Sessions are recorded and visible to the customer's company administrators
- It is visible to all `REGISTRY_ADMIN` users via the impersonation bar in the customer frontend

Impersonation is unavailable entirely when `ENTRA_ENABLED=true` — the backend refuses to mint a session on a customer's behalf. [Impersonation](../operator/customers/impersonation.md) covers the details and how to govern it.
