---
title: Luxemburg – CSSF
description: Wie Registerwerk die luxemburgischen regulatorischen Anforderungen der CSSF für tokenisierte Wertpapiere umsetzt.
---

# Luxemburg – CSSF { #luxembourg-cssf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Kontrollzuordnungen und konfigurierte Annahmen festgehalten. Sie
    stellt keine luxemburgische Rechtsberatung und keinen Nachweis der Instrumentenklassifizierung, einer
    behördlichen Zulassung, Konformität oder Rechtswirkung dar. Holen Sie eine aktuelle instrumenten-,
    betreiber-, dienst- und einsatzspezifische Prüfung ein.

Luxemburg ist Europas größter Fondsstandort und eine führende Jurisdiktion für tokenisierte Fondsinstrumente. Die **Commission de Surveillance du Secteur Financier (CSSF)** reguliert den Einsatz der Distributed-Ledger-Technologie (DLT) für Finanzinstrumente nach CSSF-Rundschreiben 19/732 und den nachfolgenden Leitlinien.

---

## Anwendbarer Regulierungsrahmen { #applicable-regulatory-framework }

| Regelwerk | Geltungsbereich |
|---|---|
| CSSF-Rundschreiben 19/732 | DLT-basierte NAV-Berechnung und Fondsverwaltung |
| CSSF-Rundschreiben 22/811 | DLT-Fondsdienstleistungen und tokenisierte Instrumente |
| AML-Gesetz von 2004 (geändert) | Sorgfaltspflichten gegenüber Kunden |
| Gesetz vom 5. April 1993 (Finanzsektor) | Zulassung von Wertpapierfirmen |
| MiCAR (EU) 2023/1114 | Kryptowerte-Dienstleister |
| DORA (EU) 2022/2554 | IKT-Betriebsresilienz |

---

## Wesentliche Unterschiede zu Deutschland { #key-differences-from-germany }

| Dimension | DE (eWpG) | LU (CSSF) |
|---|---|---|
| Maßgebliches Register | Datenbank ist maßgeblich (§16 eWpG) | Datenbank ist maßgeblich (CSSF-Leitlinien) |
| Aufbewahrungsfrist | 10 Jahre | 5 Jahre |
| MiCAR-Anwendbarkeit | Ausgenommen (eWpG-Token ≠ E-Geld-Token) | Gilt für Kryptowerte-Dienstleistungen |
| UBO-Schwellenwert | 25 % (GwG §3) | 25 % (AML-Gesetz Art. 1(7)) |
| Verstärkte Sorgfaltspflichten | PEPs (GwG §10(2)) | PEPs + Hochrisiko-Drittländer |
| Aktionärsregister | Nicht erforderlich | Erforderlich für SICAVs und SICAFs |
| Erklärung zur Herkunft der Mittel | Optional | Verpflichtend für alle Kunden |

---

## KYC-Dokumentanforderungen für `LU_CSSF` { #kyc-document-requirements-for-lu_cssf }

Zusätzlich zu den allgemeinen Dokumenten (Gründungsurkunde, Handelsregisterauszug) verlangt das Zuständigkeitsprofil `LU_CSSF`:

- **Auszug aus dem Registre des Bénéficiaires Effectifs (RBE)** – Luxemburger Register der wirtschaftlich Berechtigten
- **Aktionärsregister** – für Investmentgesellschaften (SICAV/SICAF/SIF)
- **Erklärung zur Herkunft der Mittel** – unterzeichnet vom gesetzlichen Vertreter des Kunden
- **CSSF-spezifischer AML-Fragebogen**
- Jahresberichte (letzte 2 Jahre)

Siehe [KYC & AML](../compliance/kyc-aml.md) für den vollständigen Dokumenten-Lebenszyklus.

---

## Fonds-Token-Besonderheiten { #fund-token-specifics }

Luxemburg ist der wichtigste Standort für tokenisierte Fondsinstrumente. Registerwerk unterstützt die von der CSSF bevorzugten Token-Standards für diesen Anwendungsfall:

| Instrumententyp | Token-Standard | Unterstützung durch Registerwerk |
|---|---|---|
| Synchroner Fonds (tägliche NAV) | [ERC-4626](../token-standards/erc4626.md) | Vollständig – `AssetVaultState`, `VaultNavStrike` |
| Asynchroner Fonds (T+1 / T+2) | [ERC-7540](../token-standards/erc7540.md) | Vollständig – `VaultRequest`, Request-/Claim-Ablauf |
| Anleihe mit Tranchen | [ERC-3525](../token-standards/erc3525.md) | Vollständig – `AssetSlot` (Tranche) |
| Regulierte Aktie / Anleihe | [ERC-3643](../token-standards/erc3643.md) | Vollständig – T-REX, identitätsgebunden |

Die Entität `AssetVaultState` verfolgt die NAV je Anteil. `VaultNavStrike` zeichnet jeden NAV-Berechnungspunkt auf und liefert den Aufsichtsbehörden einen zeitgestempelten Prüfpfad aller Preisentscheidungen.

---

## Abwicklungszeitpunkt { #settlement-timing }

Die aktuellen Abwicklungspflichten erfordern eine externe Prüfung. Das Modul `trading` kann einen `settledAt`-Zeitstempel aufzeichnen, aber der [MiFIR](../compliance/mifir.md)-Prototyp validiert vor der Auswahl von Datensätzen weder den Abwicklungsstatus noch ein regulatorisches Abwicklungsfenster.

---

## CSSF-Vorfallmeldung { #cssf-incident-reporting }

Nach DORA Art. 19 (in Luxemburg umgesetzt durch das DORA-Umsetzungsgesetz) müssen schwerwiegende IKT-Vorfälle der CSSF gemeldet werden:

- **Erstmeldung**: innerhalb von 4 Geschäftsstunden nach Einstufung als schwerwiegend
- **Zwischenbericht**: innerhalb von 72 Stunden
- **Abschlussbericht**: innerhalb eines Monats

Der `DoraService` speichert manuell klassifizierte Vorfälle und Zeitstempel für Fristerinnerungen. Er bestimmt nicht die rechtlich zutreffende Klassifizierung/Frist und leitet auch keine Meldungen an die CSSF weiter. Siehe [DORA](../compliance/dora.md).

---

## MiCAR-Verpflichtungen (LU_CSSF) { #micar-obligations-lu_cssf }

Die luxemburgische Umsetzung von MiCAR macht die Verordnung anwendbar auf Kryptowerte-Dienstleister, die von Luxemburg aus tätig sind. Für Registerwerk-Einsätze mit `LU_CSSF` als primärer Jurisdiktion gilt:

- Der Betreiber muss über eine CASP-Lizenz der CSSF verfügen (oder eine passportierbare Lizenz aus einem anderen EU-Mitgliedstaat)
- Die [Travel Rule](../compliance/travel-rule.md) gilt für alle Kryptowerte-Transfers ≥ 1.000 €
- Die [DAC8/CARF](../compliance/dac8.md)-Komponente erzeugt eine `DRAFT_UNVALIDATED`-Prototypausgabe; sie reicht nichts bei der ACD ein und weist auch keine behördliche Zustellung oder Annahme nach
