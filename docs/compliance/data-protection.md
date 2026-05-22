---
title: Data Protection (DSGVO / GDPR)
description: GDPR/DSGVO compliance — PII handling, encryption, ROPA, and data subject rights.
---

# Data Protection (DSGVO / GDPR)

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

## Encryption at rest

All PII fields in `NaturalPerson` are **encrypted at the column level** before storage. The encryption uses envelope encryption:

1. Each `NaturalPerson` record has a unique **Data Encryption Key (DEK)** (AES-256-GCM)
2. The DEK is wrapped with the operator's **Key Encryption Key (KEK)** (from the `wallet` module's KMS/HSM integration)
3. The encrypted DEK is stored alongside the record
4. Decryption only occurs in memory when the field is accessed; the plaintext never hits disk

S3 document files use **server-side encryption (SSE-S3 or SSE-KMS)** at the object store level.

---

## Art. 30 — Records of Processing Activities (ROPA)

Registerwerk maintains a complete ROPA covering all processing activities. Key activities documented:

| Activity | Legal basis | Retention |
|---|---|---|
| KYC/KYB identity verification | Legal obligation (GwG, TVTG, AMF) | Per jurisdiction (5–10 years) |
| Sanctions screening | Legal obligation | Per jurisdiction |
| Securities register entries | Legal obligation (eWpG, TVTG) | Per jurisdiction (5–10 years) |
| Transaction reporting (MiFIR) | Legal obligation | Per MiFIR retention rules |
| DAC8 tax reporting | Legal obligation | Per member state rules |
| Customer support communication | Legitimate interest | 3 years after last contact |
| Audit log | Legal obligation | Per jurisdiction |

The ROPA is maintained as a compliance document at `docs/compliance/ropa.md` and reviewed annually by the DPO.

---

## Art. 35 — Data Protection Impact Assessment (DPIA)

Given the sensitivity of the data and the novel technology involved, a **DPIA** is required under Art. 35. Per-jurisdiction DPIAs are maintained at:

- `docs/compliance/dpia-DE.md` — German eWpG deployment
- `docs/compliance/dpia-LU.md` — Luxembourg CSSF deployment
- `docs/compliance/dpia-FR.md` — French AMF deployment
- `docs/compliance/dpia-LI.md` — Liechtenstein TVTG deployment

Each DPIA addresses: processing purposes, necessity and proportionality, risks to data subjects, and mitigating measures.

---

## Art. 17 — Right to erasure ("right to be forgotten")

GDPR Art. 17 gives data subjects the right to request deletion of their personal data. However, Art. 17(3)(b) provides an exemption for data retained to comply with a legal obligation. For Registerwerk:

- Securities register entries **cannot be deleted** during the retention period (eWpG §15, TVTG Art. 10) — the legal obligation exemption applies
- KYC documents must be retained for the duration of the business relationship plus the retention period
- After the retention period, records are **tombstoned**: PII is permanently deleted, but the audit hash chain preserves the existence of the event (the hash of the deleted data, not the data itself)

The tombstoning process:
1. `NaturalPerson` PII fields are overwritten with `[REDACTED]` and the DEK is destroyed
2. A `NaturalPersonRedactionEvent` is written to the audit log
3. The audit hash chain continues — the hash of the redacted record proves the redaction happened at a specific time without revealing what was redacted

---

## Data subject rights endpoints

| Right | Endpoint |
|---|---|
| Art. 15 — Access | `GET /api/v1/me/dsar/export` — full JSON export of all personal data |
| Art. 16 — Rectification | `PATCH /api/v1/me/profile` — correct name, address, etc. |
| Art. 17 — Erasure | `POST /api/v1/me/dsar/erasure` — triggers tombstoning if legally permitted |
| Art. 20 — Portability | `GET /api/v1/me/dsar/export?format=json` — machine-readable export |

All DSAR (Data Subject Access Request) actions are recorded in the audit log.

---

## Art. 32 — Security of processing

Technical measures implemented:

| Measure | Implementation |
|---|---|
| Encryption in transit | TLS 1.3 on all endpoints (Kong + backend) |
| Encryption at rest | Column-level DEK/KEK for PII; SSE for S3 |
| Access control | Role-based (`@PreAuthorize`) + step-up for sensitive reads |
| Audit logging | Tamper-evident hash chain for all operations |
| MFA | WebAuthn / TOTP for all operator accounts |
| Pseudonymisation | `NaturalPerson.id` (UUID) used in cross-module references instead of name |
| Incident response | [DORA](dora.md) incident management + GDPR Art. 33/34 breach notification |

---

## Art. 33/34 — Breach notification

If a personal data breach occurs:

- Art. 33: Notify the **supervisory authority** within 72 hours of becoming aware
- Art. 34: Notify **affected data subjects** without undue delay if the breach is high-risk

The `DoraService` `MAJOR` incident classification automatically triggers a parallel GDPR breach notification workflow when the `IctIncident.category = DATA_BREACH`.
