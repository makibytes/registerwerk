---
title: France — AMF
description: How Registerwerk implements French AMF and Loi PACTE regulatory requirements for tokenised securities.
---

# France — AMF

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

When a token registered under `FR_AMF` is traded on an EU-regulated trading venue, [MiFIR RTS 22](../compliance/mifir.md) transaction reports must be filed with the **AMF via ROSA** (Reporting On Securities and Assets). The `MifirReportingService` uses the `FR_AMF` strategy to format and deliver the XML payload.

---

## TRACFIN — Suspicious transaction reporting

France's financial intelligence unit, **Tracfin**, receives AML/CTF disclosures from financial institutions. For `FR_AMF` entities, Registerwerk's sanctions screening module flags hits for COMPLIANCE_OFFICER review. Confirmed suspicious transactions trigger a structured workflow documented in the operator's AML procedures; the system records the disclosure reference in the relevant `ScreeningHit` record.

---

## DORA incident reporting (France)

Major ICT incidents must be reported to **ACPR** (for PSAN/CASP activities) and **AMF** (for securities activities). The `dora` module routes incidents with `jurisdiction = FR_AMF` to both authorities. Deadlines:

- Initial notification: 4 hours from classification as major
- Intermediate report: 72 hours
- Final report: 30 days

See [DORA](../compliance/dora.md).
