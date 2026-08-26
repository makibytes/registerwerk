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

---

## Operator roles

### REGISTRY_ADMIN

The highest-privilege role. A `REGISTRY_ADMIN` can:

- Create, update, and deactivate [Legal Entities](../intro/concepts.md#customer-entities)
- Approve and reject [KYC documents](../compliance/kyc-aml.md)
- Deploy and manage [security tokens](../token-standards/index.md)
- Issue [Sperrvermerk](../compliance/sperrvermerk.md) (trading restrictions) — requires [step-up authentication](../compliance/step-up-mfa.md)
- Force-transfer and force-burn tokens — requires step-up + 4-eyes
- Impersonate customer users for support purposes — a standing capability, see the caveat below
- Access all [audit log](../platform/audit-log.md) records
- Trigger [MiFIR](../compliance/mifir.md) and [DAC8](../compliance/dac8.md) regulatory exports

!!! warning "Force operations require dual control"
    Force-transfer, force-burn, and force-approve are irreversible on-chain operations. The current implementation requires a second, distinct `REGISTRY_ADMIN` to provide the dual-control token; there is no `SECOND_APPROVER` application role. Its legal and policy adequacy requires external review.

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

### Dual-control approver

Dual-control approval is currently a capability of a second, distinct `REGISTRY_ADMIN`, not a separate application role. The approver must be different from the initiator and must satisfy the configured step-up checks.

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
- These actions are reported to regulators via [MiFIR RTS 22](../compliance/mifir.md)

---

## Impersonation

`REGISTRY_ADMIN` users can impersonate a customer user to investigate issues or assist with onboarding. Impersonation:

- Mints a short-lived token whose `sub` remains the **operator's** user id, so every action is attributed to the operator and never to the customer
- Is recorded in the [audit log](../platform/audit-log.md), flagged with `imp` so impersonated actions are distinguishable
- Is visible to all `REGISTRY_ADMIN` users via the impersonation bar in the customer frontend
- Expires with the token; re-enter rather than extending

!!! warning "Impersonation is not step-up protected"
    `AdminImpersonationController` carries no `@RequiresStepUp`. Any `REGISTRY_ADMIN` can enter any customer's portal without a second authentication challenge and without a second person.

    Treat this as a control question rather than a technical one: keep the admin roster small, require a recorded reason outside the platform, and review impersonation events periodically. [Impersonation](../operator/customers/impersonation.md) covers governing it.

Impersonation is also unavailable entirely when `ENTRA_ENABLED=true` — the backend refuses to mint a session on a customer's behalf.
