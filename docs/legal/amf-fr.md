---
title: France — AMF
description: How Registerwerk implements French AMF and Loi PACTE regulatory requirements for tokenised securities.
---

# France — AMF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings and configured assumptions. It is not French legal
    advice or evidence of instrument classification, regulatory authorisation, compliance, or
    legal effect. Obtain current instrument-, operator-, service-, and deployment-specific review.

France created one of Europe's first dedicated legal frameworks for token-based financial instruments through the **Loi PACTE** (Plan d'Action pour la Croissance et la Transformation des Entreprises, 2019). The **Autorité des Marchés Financiers (AMF)** supervises issuers and service providers.

---

## Applicable regulatory framework

| Regulation | Scope |
|---|---|
| Loi PACTE 2019-486 | Token-based securities (minibons, titres financiers) |
| Code monétaire et financier (CMF) | Investment services, AML |
| AMF General Regulation (Règlement général) | Market conduct, prospectus, token issuance |
| AMF DOC-2022-15 | Guidance for DASPs (Digital Asset Service Providers) |
| ACPR PSAN guidance | AML for PSAN-registered entities |
| MiCAR (EU) 2023/1114 | Full applicability for CASPs |
| DORA (EU) 2022/2554 | ICT resilience |

---

## PSAN — Digital Asset Service Provider registration

French law requires entities providing digital asset services to register with the **AMF** as a **Prestataire de Services sur Actifs Numériques (PSAN)**. With the adoption of MiCAR in 2024, PSAN registration transitions to a MiCAR CASP authorisation, but existing PSAN registrations are grandfathered during a transitional period.

Registerwerk's `FR_AMF` jurisdiction profile carries the operator's PSAN/CASP registration number in configuration. This number appears in regulatory filings.

---

## Key differences from Germany

| Dimension | DE (eWpG) | FR (AMF) |
|---|---|---|
| Primary token law | eWpG (securities-specific) | Loi PACTE / CMF (general DLT) |
| Register type supported | Centralised + decentralised | DLT-based register (minibons, obligations) |
| Competent authority | BaFin | AMF (securities) + ACPR (banking/AML) |
| Retention period | 10 years | 5 years |
| KYC document — commercial register | Handelsregisterauszug | Extrait Kbis (≤ 3 months old) |
| Beneficial owner register | Transparenzregister | Registre des Bénéficiaires Effectifs (RBE) |
| AML questionnaire | GwG-specific | AMF/ACPR PSAN-specific |
| TRACFIN reporting | BaFin | AMF/ACPR forward to TRACFIN |

---

## KYC document requirements for `FR_AMF`

The `FR_AMF` jurisdiction profile in `JurisdictionRequirementConfig` requires:

- **Extrait Kbis** (≤ 3 months old from the Greffe du Tribunal de Commerce)
- **Déclaration de bénéficiaires effectifs** from the national RBE
- Statuts (articles of association)
- Identity documents for all directors and UBOs
- Annual report (last 2 years if available)
- AMF/ACPR AML questionnaire
- Source of funds declaration (for investments above AMF threshold)

---

## Minibons and titres financiers

French law allows tokenisation of two instrument categories:

**Minibons** (crowdfunding debt instruments): Short-term bonds issued through crowdfunding platforms, now eligible for DLT-based issuance under Loi PACTE.

**Titres financiers** (financial instruments): Equity and debt instruments of any kind, eligible for DLT-based issuance through a Prestataire de Compensation (central counterparty equivalent in DLT context).

Both are represented in Registerwerk using [ERC-3643](../token-standards/erc3643.md) (identity-bound, regulated) or [ERC-3525](../token-standards/erc3525.md) (tranched bonds). Deployment under `FR_AMF` triggers additional checks:

1. AMF notification of token programme (stored as `Asset.regulatoryNotificationRef`)
2. ISIN assignment verification
3. Prospectus exemption check (below €8M threshold for minibons)

---

## MiFIR reporting for France

MiFIR applicability, reporting capacity, competent authority, and channel require transaction- and
instrument-specific external review. The current [MiFIR](../compliance/mifir.md) service produces
`DRAFT_UNVALIDATED` prototype XML; it has no `FR_AMF` strategy and does not file or prove delivery
to AMF or another authority.

---

## TRACFIN — Suspicious transaction reporting

France's financial-intelligence reporting scope and process require external review. Registerwerk's
screening module records screening runs and operator review decisions, but it does not submit a
Tracfin disclosure or independently verify a disclosure reference.

---

## DORA incident reporting (France)

Authority scope and current incident-reporting deadlines require external review. The `dora`
module does not route or transmit incidents to ACPR, AMF, or another authority. The values below
are historical design assumptions, not configured filing evidence:

- Initial notification: 4 hours from classification as major
- Intermediate report: 72 hours
- Final report: 30 days

See [DORA](../compliance/dora.md).
