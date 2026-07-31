---
title: Quadri giuridici
description: Panoramica di tutte e quattro le giurisdizioni supportate e dei relativi quadri normativi.
---

# Quadri giuridici { #legal-frameworks }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste e le ipotesi configurate. Non costituisce consulenza legale o prova di conformità, autorizzazione normativa, certificazione o effetto legale.
    L'applicabilità dipende dall'operatore, dal servizio, dallo strumento, dalla transazione, dalla giurisdizione e dall'implementazione
    e deve essere approvata da un consulente qualificato e dai proprietari responsabili del controllo.

Registerwerk contiene componenti tecnici e di configurazione destinati a supportare le implementazioni in quattro giurisdizioni europee. Le tabelle seguenti sono input di revisione, non determinazioni sull'applicazione di una legge o sull'implementazione di tutti gli obblighi.

---

## Giurisdizioni supportate { #supported-jurisdictions }

| Giurisdizione | Autorità competente | Diritto primario | Quadro dei token | Conservazione | MiFIR | MiCAR |
|---|---|---|---|---|---|---|
| 🇩🇪 **Germania** | BaFin | eWpG / GwG | Kryptowertpapier (KryptoFAV) | 10 anni | Sì | No (MiCAR Art. 2(3)) |
| 🇱🇺 **Lussemburgo** | CSSF | CSSF Circ. 19/732 / AML Law 2004 | Strumenti di fondo basati su DLT | 5 anni | Sì | Sì |
| 🇫🇷 **Francia** | AMF | Code monétaire et financier / Loi PACTE | Minibons / Titres financiers | 5 anni | Sì | Sì |
| 🇱🇮 **Liechtenstein** | FMA | TVTG 2020 / SPG | Token (prestatore di servizi TT) | 10 anni | Tramite passporting | Sì |

---

## Il codice enum `Jurisdiction` { #the-jurisdiction-enum }

Nel codice, ciascuna giurisdizione è rappresentata dall'enum `Jurisdiction` nel modulo `customer`:

```java
public enum Jurisdiction {
    DE_EWPG,   // Germany — eWpG
    LU_CSSF,   // Luxembourg — CSSF
    FR_AMF,    // France — AMF
    LI_TVTG    // Liechtenstein — TVTG
}
```

A `LegalEntity` trasporta un singolo `Jurisdiction` configurato. Il codice utilizza quel valore per i profili e i flussi di lavoro selezionati; non è una decisione di classificazione dello strumento e non dimostra che un'autorità riceve un rapporto o che un periodo di conservazione configurato è legalmente corretto.

---

## Configurazione per giurisdizione { #per-jurisdiction-configuration }

La classe `JurisdictionRequirementConfig` (`kyc/api/`) contiene ipotesi di applicazione per il comportamento selezionato per giurisdizione. Non è una fonte legale di verità. Produce un bean `JurisdictionProfile` per giurisdizione, contenente valori configurati come:

- Tipi di documenti KYC richiesti (vedi [KYC e AML](../compliance/kyc-aml.md))
- Fornitori di screening delle sanzioni (OpenSanctions + Refinitiv opzionale World-Check)
- Soglia del titolare effettivo (25% in tutte e quattro le giurisdizioni)
- Cadenza di aggiornamento KYC (365 giorni per tutte, con monitoraggio rafforzato per il Lussemburgo)
- Soglia della Travel Rule (€ 1.000 in tutte le giurisdizioni)
- Autorità di vigilanza per le notifiche di incidenti DORA

---

## Obblighi comuni { #common-obligations }

Il repository raggruppa diversi componenti tecnici sotto intestazioni di conformità comuni. La loro presenza non comprova l'esistenza o l'adempimento di un obbligo:

| Obbligo | Attuazione | Riferimento |
|---|---|---|
| Verifica dell'identità del cliente | `KycDocument`, `NaturalPerson`, `BeneficialOwner` | [KYC & AML](../compliance/kyc-aml.md) |
| Monitoraggio AML continuo | `KycMonitoringJob`, ri-screening delle sanzioni | [Screening sanzioni](../compliance/sanctions-screening.md) |
| Travel Rule / IVMS-101 | `TravelRuleProtocolPort`, `Ivms101` | [Travel Rule](../compliance/travel-rule.md) |
| Integrità del registro titoli | Catena di hash `audit_event` a prova di manomissione | [Audit Log](../platform/audit-log.md) |
| Restrizioni commerciali | `HolderBlock` (Sperrvermerk) | [Sperrvermerk](../compliance/sperrvermerk.md) |
| Gestione degli incidenti ICT | `IctIncident`, `ThirdPartyProvider` | [DORA](../compliance/dora.md) |
| Reporting delle transazioni | `MifirReportingService` | [MiFIR](../compliance/mifir.md) |
| Reporting fiscale sulle cripto-attività | Modulo `regreporting` | [DAC8 / CARF](../compliance/dac8.md) |

---

## Esplora per giurisdizione { #explore-by-jurisdiction }

- [Germania — eWpG](ewpg.md)
- [Lussemburgo — CSSF](cssf-lu.md)
- [Francia — AMF](amf-fr.md)
- [Liechtenstein — TVTG](tvtg-li.md)
