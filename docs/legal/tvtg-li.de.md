---
title: Liechtenstein — TVTG
description: Wie Registerwerk die liechtensteinischen Sorgfaltspflichten nach TVTG (Token-Gesetz) und SPG umsetzt.
---

# Liechtenstein – TVTG (Token-Gesetz) { #liechtenstein-tvtg-token-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Kontrollzuordnungen und konfigurierte Annahmen festgehalten. Sie
    stellt keine liechtensteinische Rechtsberatung und keinen Nachweis der Instrumentenklassifizierung,
    Registrierung, behördlichen Zulassung, Konformität oder Rechtswirkung dar. Holen Sie eine aktuelle
    instrumenten-, betreiber-, dienst- und einsatzspezifische Prüfung ein.

Liechtenstein war das erste europäische Land, das umfassende tokenspezifische Rechtsvorschriften verabschiedet hat. Mit dem **Gesetz über Token und VT-Dienstleister** (Token- und VT-Dienstleister-Gesetz, TVTG, in Kraft seit 1. Januar 2020) wurde ein neutraler, technologieunabhängiger Rechtsrahmen geschaffen, der Token als Träger von Rechten jeder Art behandelt – einschließlich Finanzinstrumenten.

---

## Das TVTG-Modell { #the-tvtg-model }

Das TVTG etabliert den Begriff des **Token** als Dateneintrag in einem VT-System (Vertrauenswürdige Technologien, d. h. einem Distributed Ledger oder einem gleichwertigen kryptografisch gesicherten System). Rechte werden dem Token zugeordnet und nicht direkt dem zugrunde liegenden Vermögenswert, wodurch eine saubere rechtliche Trennung zwischen dem Recht (Token) und seiner technischen Darstellung (Blockchain) entsteht.

Das passt gut zum kanonischen Registermodell von Registerwerk: Der Registereintrag ist das rechtsmaßgebliche Instrument; die Blockchain ist eine Darstellung.

---

## Anwendbarer Regulierungsrahmen { #applicable-regulatory-framework }

| Regelwerk | Geltungsbereich |
|---|---|
| TVTG (LGBl. 2019 Nr. 301) | Token-Klassifizierung, Lizenzierung von Dienstleistern |
| SPG (Sorgfaltspflichtgesetz) | Sorgfaltspflichten / AML für VT-Dienstleister |
| VPG (Vermögensverwaltungsgesetz) | Vermögensverwaltungspflichten |
| FMA-Wegleitung TVTG | Aufsichtsrechtliche Leitlinien der liechtensteinischen FMA |
| MiCAR (EU) 2023/1114 | Gilt über das EWR-Abkommen |
| DORA (EU) 2022/2554 | IKT-Resilienz über das EWR-Abkommen |

---

## VT-Dienstleister-Lizenz { #tt-service-provider-licence }

Unternehmen, die ein VT-System für Finanzinstrumente betreiben, benötigen eine **VT-Dienstleister**-Lizenz der **Finanzmarktaufsicht (FMA)**. Die Konfiguration `LI_TVTG` von Registerwerk speichert die Lizenznummer des Betreibers. Der Lizenztyp bestimmt, welche Dienste erbracht werden dürfen; Registerwerk zielt auf die Dienstleistungskategorien **VT-Token-Emittent** und **VT-Registerbetreiber** ab.

---

## TVTG §9 – Pflicht zum Token-Whitepaper { #tvtg-9-token-whitepaper-obligation }

Anders als Deutschland (kein Whitepaper für elektronische Wertpapiere als solche erforderlich) und Frankreich (AMF-Informationsdokument) verlangt TVTG §9 ein **Token-Whitepaper** für jedes öffentliche Token-Angebot. Das Whitepaper muss Folgendes beschreiben:

- Die durch den Token verbrieften Rechte
- Die technische Spezifikation
- Risiken für Token-Inhaber
- Die Allgemeinen Geschäftsbedingungen

**Umsetzung:** Registerwerk speichert das Token-Whitepaper-Dokument in der Tabelle `kyc_document` unter dem Typ `TOKEN_WHITEPAPER`. Bei `LI_TVTG`-Emittenten blockiert der Deployment-Workflow die Token-Ausgabe, bis dem Asset ein `TOKEN_WHITEPAPER`-Dokument mit `status = APPROVED` zugeordnet ist.

---

## Prüfungspflicht für den Smart Contract { #smart-contract-audit-requirement }

Die FMA-Leitlinien empfehlen eine unabhängige Prüfung des Smart-Contract-Codes vor der öffentlichen Emission (für bestimmte Lizenzkategorien ist sie verpflichtend). Registerwerk speichert den Prüfbericht als `kyc_document` vom Typ `SMART_CONTRACT_AUDIT`.

---

## SPG – Sorgfaltspflichten { #spg-due-diligence-obligations }

Das **Sorgfaltspflichtgesetz** legt VT-Dienstleistern AML-/CFT-Sorgfaltspflichten auf, die den Anforderungen von AMLD5/AMLD6 entsprechen. Wesentliche Unterschiede zum deutschen GwG:

| Aspekt | DE (GwG) | LI (SPG) |
|---|---|---|
| UBO-Schwellenwert | 25 % | 25 % |
| PEP-Screening | Verpflichtend | Verpflichtend |
| Aufbewahrungsfrist | 6 Jahre (GwG §8) | 10 Jahre (TVTG Art. 10) |
| Politisch exponierte Personen | Vollständig verstärkte Sorgfaltspflichten | Vollständig verstärkte Sorgfaltspflichten + FMA-Meldung |
| Register der wirtschaftlich Berechtigten | Transparenzregister | Liechtensteinisches Handelsregister (Abschnitt UBO) |

---

## KYC-Dokumentanforderungen für `LI_TVTG` { #kyc-document-requirements-for-li_tvtg }

Das Zuständigkeitsprofil `LI_TVTG` verlangt:

- **Handelsregisterauszug** (liechtensteinischer Handelsregisterauszug, ≤ 3 Monate)
- **UBO-Erklärung**, abgestimmt auf das liechtensteinische Registerformat
- Identitätsdokumente für Geschäftsführer und wirtschaftlich Berechtigte (UBOs)
- **Token-Whitepaper** (`TOKEN_WHITEPAPER`) – verpflichtend, muss vor dem Deployment genehmigt sein
- **Smart-Contract-Prüfbericht** (`SMART_CONTRACT_AUDIT`) – verpflichtend bei öffentlichen Angeboten
- **VT-Dienstleister-Lizenz** – Kopie oder Bestätigung
- Jahresabschlüsse (letzte 2 Jahre)

---

## Aufbewahrung: 10 Jahre { #retention-10-years }

Liechtenstein verlangt eine 10-jährige Aufbewahrung aller Aufzeichnungen im Zusammenhang mit Token-Transaktionen – ebenso lang wie Deutschland, aber länger als Luxemburg und Frankreich. Das Zuständigkeitsprofil `LI_TVTG` legt `retentionYears = 10` fest.

---

## MiFIR-Meldung für Liechtenstein { #mifir-reporting-for-liechtenstein }

Anwendbarkeit von MiFIR, Meldefähigkeit, zuständige Behörde und Meldekanal erfordern eine aktuelle externe Prüfung. Im `MifirReportingService` gibt es keine `LI_TVTG`-Meldestrategie; der aktuelle Dienst erzeugt lediglich den `DRAFT_UNVALIDATED`-Prototyp, der unter [MiFIR](../compliance/mifir.md) beschrieben ist.

---

## FMA-Vorfallmeldung { #fma-incident-reporting }

Anwendbarkeit von DORA/EWR, Zuständigkeit und Fristen erfordern eine aktuelle externe Prüfung. Das Modul `dora` leitet keine `LI_TVTG`-Vorfallmeldungen an die FMA weiter und übermittelt sie auch nicht.

---

## Warum Liechtenstein für blockchain-native Emittenten { #why-liechtenstein-for-blockchain-native-issuers }

Liechtenstein bietet den blockchain-nativsten Rechtsrahmen in Europa:

- Token werden unabhängig von der zugrunde liegenden Technologie rechtlich anerkannt
- Jedes Recht kann tokenisiert werden – Finanzinstrumente, Immobilien, IP-Rechte
- Das TVTG ist technologieneutral (EVM, UTXO und DAG qualifizieren sich alle)
- Keine gesonderte Bezeichnung als „Kryptowertpapier" erforderlich – der Token selbst trägt das Recht

Das macht `LI_TVTG` attraktiv für innovative Instrumententypen wie [ERC-3525-teilfungible Anleihen](../token-standards/erc3525.md), [ERC-4626-Vault-Token](../token-standards/erc4626.md) und [DAML-Finance-Instrumente](../token-standards/canton-daml.md), für die noch kein entsprechender nationaler Instrumententyp existiert.
