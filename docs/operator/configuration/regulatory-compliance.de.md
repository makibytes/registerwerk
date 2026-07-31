---
title: Umfang der regulatorischen Compliance
---

Diese Seite legt fest, was Registerwerk zur Compliance-Unterstützung implementiert und was beim regulierten Betreiber verbleibt.

## Wichtiger Haftungsausschluss

Registerwerk ist Compliance-ermöglichende Software, keine Software zur rechtlichen Entscheidungsfindung. Regulatorische Pflichten hängen von der Jurisdiktion, dem Lizenzumfang und der aufsichtsrechtlichen Auslegung ab.

## Erfasste Jurisdiktionsprofile

Registerwerk enthält Jurisdiktionskennungen und konfigurierbare KYC-Anforderungsprofile für:

- `DE_EWPG` (Deutschland, BaFin, eWpG-Kontext)
- `LU_CSSF` (Luxemburg, CSSF)
- `FR_AMF` (Frankreich, AMF)
- `LI_TVTG` (Liechtenstein, FMA, TVTG-Kontext)

Diese Profile sind operative Kontrollen für Dokumentenerfassung und Genehmigungs-Workflows. Sie stellen keine Rechtsberatung dar und müssen vor dem Produktivbetrieb von Rechts-/Compliance-Teams geprüft werden.

## Von der Plattform implementierte Kontrollen

- Jurisdiktionsspezifische Auswertung der KYC-Dokumenten-Checkliste.
- Genehmigungsstatus je Jurisdiktion mit Ablauf und Ablehnungsgrund.
- Verpflichtende Begründung (`overrideNote`) für Genehmigungen, wenn erforderliche Nachweise fehlen, abgelaufen oder zu alt sind.
- Unveränderlicher Audit-Ereignisstrom für KYC-Einreichungen, Genehmigungen, Ablehnungen und Overrides.
- Eigene Override-Report-API (`/api/v1/audit/reports/kyc-overrides`) für Prüfungsausschüsse.
- Autorisierung auf API-Ebene für sensible KYC-Aktionen.
- Bausteine zur Datenaufbewahrung in PostgreSQL/S3 mit kontrollierten Abrufpfaden.

## Kontrollen außerhalb des Plattformumfangs

Betreiber bleiben verantwortlich für:

- Lizenzierungs- und Registrierungsstatus bei den zuständigen Behörden.
- AML/CFT-Risikomethodik und Meldepflichten für verdächtige Aktivitäten.
- Qualität, Feinabstimmung und Eskalationsrichtlinie des Sanktionsprüfungs-Anbieters.
- Standards zur Verifizierung wirtschaftlich Berechtigter und deren Beweishinlänglichkeit.
- Rechtliche Qualifizierung und Offenlegungspflichten nach MiCA/MiFID/eWpG.
- Datenschutzrechtliche Governance (Rechtsgrundlage, DSFA-Entscheidungen, Übermittlungsmechanismen, Governance für Betroffenenanfragen).

## Für die Basisausrichtung herangezogene regulatorische Referenzen

- Deutschland: eWpG-Struktur und Registerpflichten.
- EU: Grundsätze des MiCA-Rahmenwerks für Krypto-Dienstleistungen.
- EU: DSGVO-Grundsätze für rechtmäßige Verarbeitung, Datenminimierung, Sicherheit, Rechenschaftspflicht.
- Globale AML-Basis: risikobasierter Ansatz der FATF-Empfehlungen.

## Empfohlenes Governance-Paket für Betreiber

Pflegen Sie vor dem Go-live diese Artefakte außerhalb des Quellcodes und überprüfen Sie sie regelmäßig:

- Jurisdiktionsbezogenes Rechtsmemo zu Produktumfang und Lizenzgrenzen.
- KYC/AML-Richtlinie mit Eskalationsmatrix und Genehmigungsbefugnisebenen.
- Betriebsverfahren für Sanktions- und Transaktionsüberwachung.
- Register der Datenschutzkontrollen (Aufbewahrung, Zugriffskontrolle, Vorfallreaktion).
- Änderungsmanagementprozess für Aktualisierungen der Jurisdiktionsprofile und rechtliche Freigabe.
