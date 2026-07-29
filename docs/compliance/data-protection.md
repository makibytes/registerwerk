---
title: Data Protection (DSGVO / GDPR)
description: Personal-data inventory and partial DSAR workflows, with current encryption and coverage gaps.
---

# Data Protection (DSGVO / GDPR)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended privacy-control mappings and current repository behavior. It is
    not a GDPR/DSGVO compliance assessment, approved ROPA, DPIA, retention decision, or legal
    basis determination. The controller/processor roles, purposes, lawful bases, data inventory,
    retention, rights handling, and security measures require deployment-specific review by the
    controller, DPO, security owners, and qualified counsel.

**Regulation (EU) 2016/679** (GDPR, or DSGVO in German) applies to all personal data processed by Registerwerk operators. As a securities registry that processes names, dates of birth, tax IDs, passport numbers, and financial data of natural persons, Registerwerk is a data controller (and sometimes processor) subject to GDPR's full obligations.

---

## Personal data in Registerwerk

The primary location of personal data is the `NaturalPerson` entity. This includes:

| Field | GDPR category | Purpose |
|---|---|---|
| `givenName`, `familyName` | Personal data | KYC identity verification |
| `dateOfBirth` | Personal data | KYC identity verification |
| `nationality`, `countryOfResidence` | Personal data | Sanctions screening, reporting |
| `taxId`, `taxIdCountry` | Sensitive personal data | DAC8/CARF reporting |
| `address` fields | Personal data | KYC verification, document correspondence |
| `pepStatus` | Special category (political) | Enhanced due diligence |
| Document files (passports, ID cards) | Sensitive personal data | KYC verification — stored in S3 |

---

## Encryption at rest — not implemented for `NaturalPerson` fields

`NaturalPerson` PII is currently mapped to ordinary database columns. The repository does not implement application-level column encryption, per-record DEKs, KEK wrapping, or cryptographic erasure for these fields. Database-volume and object-store encryption may be configured externally, but must be verified in each deployment and does not replace application-level controls where those are required.

---

## Art. 30 — Records of Processing Activities (ROPA)

The repository contains a draft ROPA document and an initial processing-activity inventory. Completeness, legal bases, retention periods, ownership, and approval are not established by the repository:

| Activity | Legal basis | Retention |
|---|---|---|
| KYC/KYB identity verification | Legal obligation (GwG, TVTG, AMF) | Per jurisdiction (5–10 years) |
| Sanctions screening | Legal obligation | Per jurisdiction |
| Securities register entries | Legal obligation (eWpG, TVTG) | Per jurisdiction (5–10 years) |
| Transaction reporting (MiFIR) | Legal obligation | Per MiFIR retention rules |
| DAC8 tax reporting | Legal obligation | Per member state rules |
| Customer support communication | Legitimate interest | 3 years after last contact |
| Audit log | Legal obligation | Per jurisdiction |

The draft is stored at `docs/compliance/ropa.md`. A deployment must assign an owner, complete and approve it, record review evidence, and set a review cadence.

---

## Art. 35 — Data Protection Impact Assessment (DPIA)

The repository contains per-jurisdiction DPIA drafts. Whether a DPIA is required, and whether a draft is complete and approved, must be determined for the deployment:

- `docs/compliance/dpia-DE.md` — German eWpG deployment
- `docs/compliance/dpia-LU.md` — Luxembourg CSSF deployment
- `docs/compliance/dpia-FR.md` — French AMF deployment
- `docs/compliance/dpia-LI.md` — Liechtenstein TVTG deployment

These files are review inputs, not evidence of an approved DPIA.

---

## Art. 17 — Right to erasure ("right to be forgotten")

GDPR Art. 17 gives data subjects the right to request deletion of their personal data. However, Art. 17(3)(b) provides an exemption for data retained to comply with a legal obligation. For Registerwerk:

- Securities register entries **cannot be deleted** during the retention period (eWpG §15, TVTG Art. 10) — the legal obligation exemption applies
- KYC documents must be retained for the duration of the business relationship plus the retention period
- The current erasure service tombstones selected `AppUser` contact/authentication fields after operator review; it does not erase all personal data associated with an entity

Current behavior:
1. An erasure request creates an operator work item.
2. Completion replaces selected `AppUser` name/email values, clears the password hash, and disables the user.
3. `NaturalPerson`, KYC-document, holding, transaction, and other linked-data coverage is incomplete; no DEK is destroyed because per-record DEK encryption is not implemented.
4. Request/resolution events are emitted, but this alone does not prove complete erasure or legal handling of the request.

---

## Data subject rights endpoints

| Right | Endpoint |
|---|---|
| Art. 15/20 — Access/portability | `GET /api/v1/me/dsar/export` — partial legal-entity/KYC-status export; not a complete personal-data export |
| Art. 16 — Rectification | No complete DSAR rectification workflow is documented here |
| Art. 17 — Erasure | `POST /api/v1/me/dsar/erasure` — records a request for operator review; completed requests currently tombstone selected `AppUser` fields only |

The request and resolution flows emit audit events. End-to-end DSAR coverage and audit completeness remain to be verified.

---

## Art. 32 — Security of processing

Technical measures implemented:

| Measure | Implementation |
|---|---|
| Encryption in transit | TLS 1.3 on all endpoints (Kong + backend) |
| Encryption at rest | `NaturalPerson` field encryption is not implemented; deployment-level database/object-store encryption must be separately configured and verified |
| Access control | Role-based (`@PreAuthorize`) + step-up for sensitive reads |
| Audit logging | Tamper-evident hash chain for all operations |
| MFA | WebAuthn / TOTP for all operator accounts |
| Pseudonymisation | `NaturalPerson.id` (UUID) used in cross-module references instead of name |
| Incident response | Manual incident records and deadline monitoring exist; authority/data-subject notification automation is not implemented |

---

## Art. 33/34 — Breach notification

If a personal data breach occurs:

- Art. 33: Notify the **supervisory authority** within 72 hours of becoming aware
- Art. 34: Notify **affected data subjects** without undue delay if the breach is high-risk

No automatic GDPR authority or data-subject breach-notification workflow is implemented. Operators must establish, test, and evidence a deployment-specific process.
