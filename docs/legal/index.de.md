---
title: Rechtliche Rahmenbedingungen
description: Überblick über alle vier unterstützten Jurisdiktionen und ihre regulatorischen Rahmenbedingungen.
---

# Rechtliche Rahmenbedingungen { #legal-frameworks }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Kontrollzuordnungen und konfigurierte Annahmen festgehalten. Sie
    stellt keine Rechtsberatung und keinen Nachweis der Konformität, einer behördlichen Zulassung,
    Zertifizierung oder Rechtswirkung dar. Die Anwendbarkeit hängt vom Betreiber, Dienst, Instrument, der
    Transaktion, der Jurisdiktion und dem Einsatz ab und muss von qualifizierten Rechtsberatern und den
    zuständigen Kontrollverantwortlichen freigegeben werden.

Registerwerk enthält Konfigurations- und technische Komponenten, die den Einsatz in vier europäischen Jurisdiktionen unterstützen sollen. Die folgenden Tabellen sind Prüfungsgrundlagen, keine Feststellungen, dass ein Gesetz anwendbar ist oder dass alle Pflichten umgesetzt wurden.

---

## Unterstützte Jurisdiktionen { #supported-jurisdictions }

| Jurisdiktion | Zuständige Behörde | Primäres Recht | Token-Framework | Aufbewahrung | MiFIR | MiCAR |
|---|---|---|---|---|---|---|
| 🇩🇪 **Deutschland** | BaFin | eWpG / GwG | Kryptowertpapier (KryptoFAV) | 10 Jahre | Ja | Nein (MiCAR Art. 2(3)) |
| 🇱🇺 **Luxemburg** | CSSF | CSSF-Rundschreiben 19/732 / AML-Gesetz 2004 | DLT-basierte Fondsinstrumente | 5 Jahre | Ja | Ja |
| 🇫🇷 **Frankreich** | AMF | Code monétaire et financier / Loi PACTE | Minibons / titres financiers | 5 Jahre | Ja | Ja |
| 🇱🇮 **Liechtenstein** | FMA | TVTG 2020 / SPG | Token (VT-Dienstleister) | 10 Jahre | Über Passporting | Ja |

---

## Die `Jurisdiction`-Enumeration { #the-jurisdiction-enum }

Im Code wird jede Jurisdiktion durch die Enumeration `Jurisdiction` im Modul `customer` dargestellt:

```java
public enum Jurisdiction {
    DE_EWPG,   // Germany — eWpG
    LU_CSSF,   // Luxembourg — CSSF
    FR_AMF,    // France — AMF
    LI_TVTG    // Liechtenstein — TVTG
}
```

Eine `LegalEntity` trägt eine einzelne konfigurierte `Jurisdiction`. Der Code verwendet diesen Wert für ausgewählte Profile und Workflows; es handelt sich nicht um eine Instrumentenklassifizierungsentscheidung, und er belegt weder, dass eine Behörde eine Meldung erhält, noch dass eine konfigurierte Aufbewahrungsfrist rechtlich zutreffend ist.

---

## Konfiguration je Jurisdiktion { #per-jurisdiction-configuration }

Die Klasse `JurisdictionRequirementConfig` (`kyc/api/`) enthält Anwendungsannahmen für ausgewähltes jurisdiktionsspezifisches Verhalten. Sie ist keine rechtliche Quelle der Wahrheit. Sie erzeugt eine `JurisdictionProfile`-Bean je Jurisdiktion mit konfigurierten Werten wie:

- Erforderliche KYC-Dokumenttypen (siehe [KYC & AML](../compliance/kyc-aml.md))
- Anbieter der Sanktionsprüfung (OpenSanctions + optional Refinitiv World-Check)
- Schwellenwert für wirtschaftlich Berechtigte (25 % in allen vier Jurisdiktionen)
- Aktualisierungsrhythmus für KYC (365 Tage für alle, mit verstärkter Überwachung für Luxemburg)
- Travel-Rule-Schwellenwert (1.000 € einheitlich)
- Aufsichtsbehörde für DORA-Vorfallmeldungen

---

## Gemeinsame Pflichten { #common-obligations }

Das Repository gruppiert mehrere technische Komponenten unter gemeinsamen Compliance-Überschriften. Ihr Vorhandensein belegt nicht, dass eine Pflicht besteht oder erfüllt wurde:

| Pflicht | Umsetzung | Referenz |
|---|---|---|
| Überprüfung der Kundenidentität | `KycDocument`, `NaturalPerson`, `BeneficialOwner` | [KYC & AML](../compliance/kyc-aml.md) |
| Laufende AML-Überwachung | `KycMonitoringJob`, Sanktionswiederholungsprüfung | [Sanktionsprüfung](../compliance/sanctions-screening.md) |
| Travel Rule / IVMS-101 | `TravelRuleProtocolPort`, `Ivms101` | [Travel Rule](../compliance/travel-rule.md) |
| Integrität des Wertpapierregisters | Manipulationssicher nachweisbare `audit_event`-Hash-Kette | [Audit-Log](../platform/audit-log.md) |
| Handelsbeschränkungen | `HolderBlock` (Sperrvermerk) | [Sperrvermerk](../compliance/sperrvermerk.md) |
| IKT-Vorfallmanagement | `IctIncident`, `ThirdPartyProvider` | [DORA](../compliance/dora.md) |
| Transaktionsmeldungen | `MifirReportingService` | [MiFIR](../compliance/mifir.md) |
| Steuermeldung für Kryptowerte | Modul `regreporting` | [DAC8 / CARF](../compliance/dac8.md) |

---

## Nach Jurisdiktion erkunden { #explore-by-jurisdiction }

- [Deutschland – eWpG](ewpg.md)
- [Luxemburg – CSSF](cssf-lu.md)
- [Frankreich – AMF](amf-fr.md)
- [Liechtenstein – TVTG](tvtg-li.md)
