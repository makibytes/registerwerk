---
title: Legal Frameworks
description: Overview of all four supported jurisdictions and their regulatory frameworks.
---

# Legal Frameworks

Registerwerk is designed to operate simultaneously under four European legal frameworks. Each framework defines requirements for securities registration, KYC/AML, data retention, incident reporting, and investor protection. The platform maps every legal obligation to a concrete implementation.

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

A `LegalEntity` carries a single `Jurisdiction`, which determines which KYC documents are required, which screening adapters are applied, which supervisory authority receives incident reports, and how long records must be retained.

---

## Per-jurisdiction configuration

The `JurisdictionRequirementConfig` class (`kyc/api/`) is the single source of truth for per-jurisdiction behaviour. It is a Spring `@Configuration` that produces one `JurisdictionProfile` bean per jurisdiction, containing:

- Required KYC document types (see [KYC & AML](../compliance/kyc-aml.md))
- Sanctions screening providers (OpenSanctions + optional Refinitiv World-Check)
- Beneficial owner threshold (25% across all four jurisdictions)
- KYC refresh cadence (365 days for all, with enhanced monitoring for Luxembourg)
- Travel Rule threshold (€1,000 across all)
- Supervisory authority for DORA incident notifications

---

## Common obligations

Despite different primary laws, all four jurisdictions share a common set of obligations that Registerwerk implements once and reuses:

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
