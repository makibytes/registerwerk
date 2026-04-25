---
id: onboarding-flow
title: Customer Onboarding Flow
sidebar_label: Onboarding Flow
sidebar_position: 1
---

# Customer Onboarding Flow

This page walks through the complete process of onboarding a new issuer, investor, or auditor entity as an operator.

## Overview

The onboarding flow:

```
Operator creates entity in operator frontend
        |
        v
System generates a one-time onboarding token
        |
        v
System sends invitation email to entity contact
        |
        v
Entity redeems token in customer portal
        |
        v
Entity configures organization profile and IdP (optional)
        |
        v
Operator activates the entity (if manual activation required)
        |
        v
Entity is live
```

## Step 1 — Create the entity

Navigate to **Onboarding → Entities → New Entity** in the operator frontend.

Fill in the entity form:

| Field | Required | Description |
|-------|----------|-------------|
| Legal name | Yes | Registered company name |
| Entity type | Yes | Issuer, Investor, or Auditor |
| Contact email | Yes | Email for the invitation |
| LEI | Yes (issuers) | Legal Entity Identifier |
| Country | Yes | Country of incorporation |
| Notes | No | Internal operator notes |

Click **Create Entity**. The system:
1. Creates the entity record in the database
2. Generates a one-time onboarding token (valid for 48 hours by default, configurable via `registerwerk.onboarding.token-ttl-hours`)
3. Sends the invitation email to the contact address

## Step 2 — Monitor token redemption

On the entity detail page, the **Onboarding Status** panel shows:

- `TOKEN_SENT` — email dispatched, awaiting redemption
- `TOKEN_REDEEMED` — entity has logged in and redeemed the token
- `ACTIVE` — fully onboarded and active

If the token expires before redemption, click **Resend Invitation** to generate a new token and send a fresh email.

## Step 3 — KYC review (issuers and investors)

After token redemption, issuers and investors are prompted to submit KYC documents. See [KYC Process](./kyc-process) for the full review workflow.

Auditors do not require KYC — they are activated immediately after token redemption.

## Step 4 — Activate the entity

For issuers, the entity becomes fully active after the operator completes the KYC review and approves. For investors, the entity is active once KYC is approved and the first wallet is connected.

To manually activate or deactivate an entity:

```bash
curl -X PATCH http://localhost:8080/api/v1/admin/entities/{entityId}/status \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"status": "ACTIVE"}'
```

Valid statuses: `ACTIVE`, `SUSPENDED`, `PENDING_KYC`.

## Managing existing entities

### Viewing all entities

Navigate to **Onboarding → Entities**. Filter by type, status, or country. Click any entity to see full details including users, roles, KYC status, and associated issuances.

### Suspending an entity

Suspending an entity blocks all users from that entity from logging in and freezes any active tokens issued by that entity.

Navigate to **Entities → [entity] → Actions → Suspend**.

### Merging entities (duplicate detection)

If duplicate entities are discovered, navigate to **Entities → [entity] → Actions → Merge with...** and select the canonical entity. The merge operation:
1. Re-links all issuances, investors, and audit log entries to the canonical entity
2. Deactivates the duplicate
3. Records the merge in the `entity_merge_records` table for audit purposes

# Customer Onboarding Flow

## Overview

```
Operator creates entity → Generates onboarding token → Sends email →
Customer redeems token → Sets up IdP users → Entity becomes ACTIVE
```

## Step 1 — Create a legal entity

```bash
curl -X POST http://localhost:8000/api/v1/entities \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{
    "type": "ISSUER",
    "currentName": "ACME AG",
    "leiCode": "529900T8BM49AURSDO55",
    "registrationCountry": "DE"
  }'
```

Entity is created with status `PENDING_ONBOARDING`.

## Step 2 — Generate onboarding token

```bash
curl -X POST http://localhost:8000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

The backend:
1. Generates a cryptographically random 48-character token
2. Stores its **SHA-256 hash** in `onboarding_token` (cleartext never stored)
3. Sends a welcome email with a link containing the plaintext token
4. Token expires after 48 hours

## Step 3 — Customer redeems token

The customer opens the link in the customer frontend:
```
https://customer.yourregistry.de/onboarding/setup?token=<token>
```

The public endpoint `/api/v1/onboarding/token-info/{token}` validates the token and returns the entity name (no auth required).

## Step 4 — Setup complete

After IdP configuration, the entity transitions to `ACTIVE` status.

## Roles

| Role | Access |
|---|---|
| `ROLE_REGISTRY_ADMIN` | Full access to all entities and assets |
| `ROLE_ISSUER` | Own entity, own assets |
| `ROLE_INVESTOR` | Own holdings |
| `ROLE_COMPANY_ADMIN` | User management for own entity |
| `ROLE_AUDIT` | Read-only access to audit log |
