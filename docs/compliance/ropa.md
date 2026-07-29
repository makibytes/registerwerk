# Verzeichnis von Verarbeitungstätigkeiten (DSGVO Art. 30)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This repository document is a draft inventory, not an approved or complete Article 30 record.
    The deployment controller/processor must establish scope, purposes, lawful bases, recipients,
    transfers, retention, security measures, ownership, approval, and review evidence.
# Records of Processing Activities (GDPR Art. 30)

**Controller:** [Operator name to fill in]  
**DPO:** [Contact to fill in]  
**Last updated:** 2026-05-21  
**Version:** 1.0

---

## 1. Customer Onboarding & KYC

| Field | Value |
|---|---|
| **Purpose** | Customer identity verification and onboarding for electronic securities issuance (GwG §10, eWpG §3) |
| **Legal basis** | Legal obligation (DSGVO Art. 6(1)(c)) — GwG §10, eWpG |
| **Data categories** | Legal entity name, LEI, registration number, incorporation date, KYC documents (registration extract, UBO declaration, ID documents, board resolutions), KYC status |
| **Natural persons** | Directors, UBOs: name, date of birth, nationality, address, ID document type/number, PEP/sanctions status |
| **Recipients** | BaFin (DE), CSSF (LU), AMF (FR), FMA (LI) — upon regulatory request only |
| **Third-country transfers** | None planned; AWS S3 (eu-central-1) for document storage — Standard Contractual Clauses |
| **Retention** | 10 years post-relationship end (eWpG §15(3)); 5 years for KYC records (GwG §8) |
| **Security measures** | AES-256-GCM at rest; TLS 1.3 in transit; role-based access (COMPLIANCE_OFFICER, REGISTRY_ADMIN); audit log |

## 2. Electronic Securities Registry

| Field | Value |
|---|---|
| **Purpose** | Maintenance of electronic securities register per eWpG (Registerführung) |
| **Legal basis** | Legal obligation (Art. 6(1)(c)) — eWpG §7, §15, §16, §17 |
| **Data categories** | Asset holder: wallet address, nominal amount, acquisition date, whitelist status; transaction history |
| **Natural persons** | Holder identity for natural persons: name, date of birth, nationality, tax ID (via HolderIdentity) |
| **Recipients** | BaFin (court-ordered disclosures); issuer (per eWpG §15) |
| **Retention** | 10 years post-redemption/cancellation (eWpG §15(3)) |
| **Security measures** | Hash-chained immutable audit log; WORM trigger; daily anchor; chain drift detection |

## 3. Sanctions & PEP Screening

| Field | Value |
|---|---|
| **Purpose** | Ongoing AML/CTF screening per GwG §10 Abs. 1 Nr. 5 |
| **Legal basis** | Legal obligation (Art. 6(1)(c)) — GwG §10, MiCAR Art. 60 |
| **Data categories** | Entity name, LEI, registration number — screened against OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO |
| **Processors** | OpenSanctions (open data, GDPR-neutral); Refinitiv World-Check (DPA required) |
| **Retention** | 5 years (GwG §8) |
| **Security measures** | Screening results stored in encrypted DB; 4-eyes for accepting a hit |

## 4. Trading & Transaction Processing

| Field | Value |
|---|---|
| **Purpose** | Execution of securities transactions on trading venues (Assetera, Archax, Talos, simulated) |
| **Legal basis** | Contractual necessity (Art. 6(1)(b)); legal obligation for MiFIR reporting (Art. 6(1)(c)) |
| **Data categories** | Trader ID, entity ID, trade listings, execution records, wallet addresses |
| **Recipients** | BaFin/AMF — MiFIR RTS 22 transaction reports |
| **Retention** | 7 years (MiFIR Art. 25(1)); 5 years (GwG) |
| **Security measures** | Role-based access (TRADER); audit log per trade |

## 5. Audit Logging

| Field | Value |
|---|---|
| **Purpose** | Security and compliance audit trail; eWpRV §6 integrity requirement |
| **Legal basis** | Legal obligation (Art. 6(1)(c)) — eWpG §15, eWpRV §6, DORA Art. 9 |
| **Data categories** | Actor ID, actor role, event type, subject ID/type, payload (may include entity names) |
| **Retention** | 10 years (eWpG §15(3)); append-only, cannot be deleted |
| **Security measures** | SHA-256 hash chain; WORM DB trigger; daily anchor to public blockchain; restricted DB role |

## 6. Operator User Management

| Field | Value |
|---|---|
| **Purpose** | Authentication and authorization of registry staff |
| **Legal basis** | Legitimate interest (Art. 6(1)(f)) — IT security, access control |
| **Data categories** | Email, hashed password, roles, last login, action tokens |
| **Retention** | Duration of employment + 2 years |
| **Security measures** | BCrypt password hashing; JWT (short-lived, 8h); MFA for sensitive operations |

## 7. Regulatory Reporting (MiFIR, DAC8, Steuerbescheinigung)

| Field | Value |
|---|---|
| **Purpose** | Mandatory transaction reporting to competent authorities |
| **Legal basis** | Legal obligation (Art. 6(1)(c)) — MiFIR Art. 26, DAC8, EStG §43 |
| **Data categories** | Investor name, tax ID, holdings, transactions, IBAN (for Steuerbescheinigung) |
| **Recipients** | BaFin (DE), AMF (FR), CSSF (LU), FMA (LI), BZSt (DAC8/CARF), DGFiP (FR), ACD (LU) |
| **Retention** | 7 years (MiFIR); 10 years (eWpG) |
| **Security measures** | PAdES-B-LT signed PDFs; SFTP to authority portals; submission receipts |

---

## Data Subject Rights

| Right | Implementation |
|---|---|
| Art. 15 Access | `GET /api/v1/me/dsar/export` |
| Art. 17 Erasure | `POST /api/v1/me/dsar/erasure` — PII tombstoned; audit hash chain preserved (Art. 17(3)(b) legal obligation) |
| Art. 20 Portability | `GET /api/v1/me/dsar/export` returns JSON |
| Art. 21 Objection | Not applicable (legal obligation basis) |
| Art. 22 Automated decision | No automated decisions; all KYC approvals are human-reviewed |
