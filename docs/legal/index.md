---
title: Legal Frameworks
description: Overview of all four supported jurisdictions and their regulatory frameworks.
---

# Legal Frameworks

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings and configured assumptions. It is not legal
    advice or evidence of compliance, regulatory authorisation, certification, or legal effect.
    Applicability depends on the operator, service, instrument, transaction, jurisdiction, and
    deployment and must be approved by qualified counsel and the responsible control owners.

Registerwerk contains configuration and technical components intended to support deployments in four European jurisdictions. The tables below are review inputs, not determinations that a law applies or that every obligation has been implemented.

---

## Supported jurisdictions

| Jurisdiction | Competent Authority | Primary Law | Token Framework | Retention | MiFIR | MiCAR |
|---|---|---|---|---|---|---|
| 🇩🇪 **Germany** | BaFin | eWpG / GwG | Kryptowertpapier (KryptoFAV) | 10 years | Yes | No (MiCAR Art. 2(3)) |
| 🇱🇺 **Luxembourg** | CSSF | CSSF Circ. 19/732 / AML Law 2004 | DLT-based fund instruments | 5 years | Yes | Yes |
| 🇫🇷 **France** | AMF | Monetary Code / Loi PACTE | Minibons / Titres financiers | 5 years | Yes | Yes |
| 🇱🇮 **Liechtenstein** | FMA | TVTG 2020 / SPG | Token (TT Service Provider) | 10 years | Via passporting | Yes |

---

## The `Jurisdiction` enum

In code, each jurisdiction is represented by the `Jurisdiction` enum in the `customer` module:

```java
public enum Jurisdiction {
    DE_EWPG,   // Germany — eWpG
    LU_CSSF,   // Luxembourg — CSSF
    FR_AMF,    // France — AMF
    LI_TVTG    // Liechtenstein — TVTG
}
```

A `LegalEntity` carries a single configured `Jurisdiction`. Code uses that value for selected profiles and workflows; it is not an instrument-classification decision and does not prove that an authority receives a report or that a configured retention period is legally correct.

---

## Per-jurisdiction configuration

The `JurisdictionRequirementConfig` class (`kyc/api/`) contains application assumptions for selected per-jurisdiction behavior. It is not a legal source of truth. It produces one `JurisdictionProfile` bean per jurisdiction, containing configured values such as:

- Required KYC document types (see [KYC & AML](../compliance/kyc-aml.md))
- Sanctions screening providers (OpenSanctions + optional Refinitiv World-Check)
- Beneficial owner threshold (25% across all four jurisdictions)
- KYC refresh cadence (365 days for all, with enhanced monitoring for Luxembourg)
- Travel Rule threshold (€1,000 across all)
- Supervisory authority for DORA incident notifications

---

## Common obligations

The repository groups several technical components under common compliance headings. Their presence does not establish that an obligation applies or has been satisfied:

| Obligation | Implementation | Reference |
|---|---|---|
| Customer identity verification | `KycDocument`, `NaturalPerson`, `BeneficialOwner` | [KYC & AML](../compliance/kyc-aml.md) |
| Ongoing AML monitoring | `KycMonitoringJob`, sanctions re-screen | [Sanctions Screening](../compliance/sanctions-screening.md) |
| Travel Rule / IVMS-101 | `TravelRuleProtocolPort`, `Ivms101` | [Travel Rule](../compliance/travel-rule.md) |
| Securities register integrity | Tamper-evident `audit_event` hash chain | [Audit Log](../platform/audit-log.md) |
| Trading restrictions | `HolderBlock` (Sperrvermerk) | [Sperrvermerk](../compliance/sperrvermerk.md) |
| ICT incident management | `IctIncident`, `ThirdPartyProvider` | [DORA](../compliance/dora.md) |
| Transaction reporting | `MifirReportingService` | [MiFIR](../compliance/mifir.md) |
| Crypto-asset tax reporting | `regreporting` module | [DAC8 / CARF](../compliance/dac8.md) |

---

## Explore by jurisdiction

- [Germany — eWpG](ewpg.md)
- [Luxembourg — CSSF](cssf-lu.md)
- [France — AMF](amf-fr.md)
- [Liechtenstein — TVTG](tvtg-li.md)
