---
title: KYC & AML
description: Know Your Customer and Anti-Money Laundering implementation across all four jurisdictions.
---

# KYC & AML

Know Your Customer (KYC) / Know Your Business (KYB) is the foundation of Registerwerk's compliance stack. Every legal entity must pass KYC before it can issue or receive tokens. The implementation covers document collection, beneficial owner verification, ongoing monitoring, and per-jurisdiction requirements.

---

## KYC state machine

```mermaid
stateDiagram-v2
    [*] --> PENDING : Customer submits documents
    PENDING --> UNDER_REVIEW : Compliance officer opens review
    UNDER_REVIEW --> APPROVED : All documents verified + screening clear
    UNDER_REVIEW --> REJECTED : Document incomplete / screening hit unresolved
    APPROVED --> EXPIRING : 30 days before kyc_expiry_date (KycMonitoringJob)
    EXPIRING --> APPROVED : Customer submits renewal + re-approved
    EXPIRING --> EXPIRED : kyc_expiry_date reached
    EXPIRED --> PENDING : Customer resubmits
    REJECTED --> PENDING : Customer resubmits corrected documents
```

A `LegalEntity` is blocked from all token issuance and transfer if its KYC status is not `APPROVED`.

---

## Data model

### `KycDocument`

The core KYC record. One `LegalEntity` can have many `KycDocument` records, one per document type. Key fields:

| Field | Type | Description |
|---|---|---|
| `documentType` | Enum | Type of document (see [per-jurisdiction requirements](#per-jurisdiction-requirements)) |
| `status` | Enum | `PENDING` / `APPROVED` / `REJECTED` / `EXPIRED` |
| `jurisdiction` | `Jurisdiction` | Which jurisdiction this approval covers |
| `s3Key` | String | Object storage key for the document file |
| `expiresAt` | Instant | For time-limited documents |
| `approvedBy` | UUID | Reference to the `AppUser` who approved |
| `approvedAt` | Instant | Approval timestamp (immutable once set) |

### `KycJurisdictionApproval`

A per-jurisdiction sign-off record. One `LegalEntity` can hold separate approvals for each of the four jurisdictions, allowing a customer to operate in multiple markets with a single set of documents.

### `NaturalPerson`

Stores PII for directors, signatories, and beneficial owners. All personally identifiable fields (`givenName`, `familyName`, `dateOfBirth`, `taxId`, `address*`) are **encrypted at rest** using a per-entity DEK wrapped by the operator's KEK.

### `BeneficialOwner`

Links a `LegalEntity` to a `NaturalPerson` with:
- `ownershipPct` — ownership percentage (threshold: 25%)
- `controlType` — DIRECT / INDIRECT / OTHER
- `registeredAt` / `ceasedAt` — ownership period

---

## Per-jurisdiction requirements

=== "Germany (DE_EWPG)"

    | Document type | Required | Notes |
    |---|---|---|
    | Certificate of incorporation | ✅ | Handelregisterauszug |
    | Shareholder register | ✅ | |
    | UBO declaration | ✅ | Transparenzregister extract |
    | Identity (directors + UBOs) | ✅ | |
    | Board resolution | ✅ | Authorising token issuance |
    | Annual report | ✅ | Last 2 years |
    | GwG AML questionnaire | ✅ | |
    | LEI certificate | ✅ (recommended) | |

=== "Luxembourg (LU_CSSF)"

    | Document type | Required | Notes |
    |---|---|---|
    | Certificate of incorporation | ✅ | |
    | RCS extract | ✅ | Registre du Commerce et des Sociétés |
    | RBE extract | ✅ | Registre des Bénéficiaires Effectifs |
    | Shareholder register | ✅ | Mandatory for SICAVs and SICAFs |
    | Source of funds | ✅ | Mandatory for all LU customers |
    | CSSF AML questionnaire | ✅ | |
    | Identity (directors + UBOs) | ✅ | |
    | Annual report | ✅ | Last 2 years |

=== "France (FR_AMF)"

    | Document type | Required | Notes |
    |---|---|---|
    | Extrait Kbis | ✅ | ≤ 3 months old |
    | Statuts | ✅ | Articles of association |
    | RBE declaration | ✅ | Registre des Bénéficiaires Effectifs |
    | Identity (directors + UBOs) | ✅ | |
    | AMF/ACPR PSAN AML questionnaire | ✅ | |
    | Annual report | ✅ | Last 2 years |
    | Source of funds | ✅ (high-risk) | |

=== "Liechtenstein (LI_TVTG)"

    | Document type | Required | Notes |
    |---|---|---|
    | Handelsregisterauszug | ✅ | ≤ 3 months old |
    | UBO declaration | ✅ | FMA-aligned format |
    | Identity (directors + UBOs) | ✅ | |
    | Token whitepaper | ✅ | TVTG §9 — mandatory before deployment |
    | Smart contract audit | ✅ | FMA guidance for public offerings |
    | TT Service Provider licence | ✅ | |
    | Annual financial statements | ✅ | Last 2 years |

---

## KYC approval gate

A `LegalEntity` can only reach `APPROVED` status when:

1. All required documents for the jurisdiction are present and individually approved
2. All `BeneficialOwner` → `NaturalPerson` records are complete (ownership ≥ 25% covered)
3. The latest [sanctions screening](sanctions-screening.md) run returns no open hits
4. The [step-up authentication](step-up-mfa.md) of the approving `COMPLIANCE_OFFICER` is current

The `ScreeningGate` interface in the `screening` module is called by `KycService.approveKyc()`:

```java
// KycService.approveKyc() — simplified
if (screeningGate.hasUnresolvedHit(entityId)) {
    throw new InvalidStateTransitionException("Open sanctions hit blocks KYC approval");
}
if (screeningGate.hasUnresolvedBeneficialOwnerHit(entityId)) {
    throw new InvalidStateTransitionException("Open UBO sanctions hit blocks KYC approval");
}
```

---

## Ongoing monitoring

**GwG §10 Abs. 1 Nr. 5** and equivalents in all four jurisdictions require ongoing monitoring of business relationships.

`KycMonitoringJob` (`kyc/internal/`) runs daily at 02:00 UTC:

1. Fetches all `LegalEntity` records with `kycStatus = APPROVED`
2. If `kycExpiryDate` is within 30 days → transitions to `EXPIRING`, emits `KycExpiringEvent` → email notification to the customer's `COMPANY_ADMIN`
3. If `kycExpiryDate` has passed → transitions to `EXPIRED`, emits `KycExpiredEvent` → triggers removal from [ERC-3643 identity registry](../token-standards/erc3643.md)

Additionally, the `ScreeningService` runs nightly to re-screen all active entities against the latest sanctions lists. A newly discovered hit transitions the entity to a `SCREENING_REVIEW` flag and notifies the `COMPLIANCE_OFFICER`.
