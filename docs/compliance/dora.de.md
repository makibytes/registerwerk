---
title: DORA — IKT-Risikomanagement
description: Prototyp für IKT-Vorfall-, Resilienztest- und Drittanbieteraufzeichnungen; keine vollständige DORA-Implementierung.
---

# DORA – Digital Operational Resilience Act { #dora-digital-operational-resilience-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Kontrollzuordnungen und das aktuelle Repository-Verhalten
    aufgezeichnet. Es handelt sich nicht um Rechtsberatung oder einen Nachweis dafür, dass DORA auf
    einen bestimmten Betreiber zutrifft, dass ein vollständiger Kontrollrahmen existiert, oder dass
    ein Vorfall gültig klassifiziert oder gemeldet wurde. Anwendbarkeit, Klassifizierung, Fristen,
    zuständige Behörden, Formulare, Kanäle und Nachweise erfordern eine aktuelle betreiber-,
    dienst-, vorfall-, gerichtsbarkeits- und einsatzspezifische Überprüfung durch qualifizierte
    Rechtsberater und die verantwortlichen Resilienz- und Compliance-Verantwortlichen.

Das Repository enthält eine manuelle Betriebsaufzeichnung für IKT-Vorfälle, Resilienztests und Drittanbieter. Es handelt sich nicht um eine Implementierung der Behördenmeldung.

## Umfang und Anwendbarkeit { #scope-and-applicability }

DORA-Anwendbarkeit kann nicht aus dem Repository-Namen, einem `eWpG`-Gerichtsbarkeitswert, einem Token-Standard oder dem Vorhandensein eines `dora`-Moduls abgeleitet werden. Die regulierten Kapazitäten des Betreibers und die tatsächlich erbrachten Leistungen müssen extern klassifiziert werden, bevor auf eine Kontrollzuordnung zurückgegriffen wird.

Aussagen zum jeweils geltenden Recht in Bezug auf DORA-Artikel, technische Standards, Klassifizierungsschwellen und Meldefristen müssen im Rahmen dieser Prüfung anhand aktueller amtlicher Quellen überprüft werden.

## Aktueller Vorfallsdatensatz { #current-incident-record }

Ein autorisierter Betreiber kann über `POST /api/v1/dora/incidents` manuell einen `IctIncident` erstellen. Die aktuelle Entität erfasst:

- Kategorie: `DATA_BREACH`, `SYSTEM_OUTAGE`, `RANSOMWARE`, `THIRD_PARTY_FAILURE` oder `OTHER`;
- Schweregrad: `LOW`, `MEDIUM`, `HIGH` oder `MAJOR`;
- Status: `DETECTED`, `INVESTIGATING`, `CONTAINED`, `RESOLVED`,
  `REPORTED_TO_AUTHORITY` oder `CLOSED`;
- Beschreibung, Quellenereignisbezeichnungen, Zeitstempel, Grundursache, Behebung, Zuweisung und eine vom Betreiber eingegebene Behördenreferenz;
- von der Anwendung berechnete Erinnerungszeitstempel für Vorfälle, die als `MAJOR` eingegeben wurden.

Diese Werte sind vom Betreiber eingegebene operative Daten. Ein Status wie `REPORTED_TO_AUTHORITY` oder ein `authorityRef` zeichnet eine Zusicherung des Betreibers auf; die Anwendung überprüft den Eingang oder die Annahme durch eine Behörde nicht unabhängig.

## Fristenüberwachung { #deadline-monitoring }

`DoraService` führt einen täglichen Job aus, der überfällige Anwendungsfristen abfragt und Log-Meldungen schreibt. Er stellt außerdem Kennzahlen (Gauges) für überfällige Datensätze bereit. Der Job übermittelt keine Benachrichtigung, erstellt keinen behördlich formatierten Bericht, weist nicht nach, dass die konfigurierte Frist rechtlich korrekt ist, und benachrichtigt nicht alle verantwortlichen Mitarbeiter.

Das aktuelle Modell bildet keinen vollständigen Workflow für Erst-, Zwischen- und Abschlussmeldungen ab. Betreiber dürfen dessen Zeitstempel ohne aktuelle rechtliche und aufsichtsrechtliche Prüfung nicht als gesetzliche Fristen verwenden.

## Automatische Vorfallserkennung – nicht implementiert { #automatic-incident-detection-not-implemented }

Interne Audit-, Chain-Drift-, Indexer-, RPC- oder Screening-Ereignisse werden nicht automatisch klassifiziert und in `IctIncident`-Datensätze umgewandelt. `sourceEventType` und `sourceEventRef` sind manuell bereitgestellte Korrelationsfelder, kein Beweis für eine automatisierte Erkennungspipeline.

## IKT-Drittanbieter-Aufzeichnungen { #ict-third-party-records }

Die Entität `ThirdPartyProvider` speichert operative Felder, darunter Name, Kategorie, Kritikalität, LEI, Land, Vertragsdaten, Sub-Outsourcing-Hinweise, Kontakt, SLA, RTO/RPO und ein vom Betreiber verwaltetes Benachrichtigungsflag. Die Datensätze werden über folgende Endpunkte aufgelistet:

- `GET /api/v1/dora/providers`
- `GET /api/v1/dora/providers/expiring`

Diese Tabelle ist kein vollständiges oder von der Behörde genehmigtes DORA-Informationsregister. Es ist kein behördentauglicher, schemavalidierter Art.-28-Export implementiert.

## Resilienztest-Datensätze { #resilience-test-records }

Das Modul kann Resilienztest-Metadaten aufzeichnen und auflisten und Datensätze hervorheben, deren konfiguriertes nächstes Fälligkeitsdatum überschritten ist. Es führt weder einen Resilienztest durch noch validiert es dessen Nachweise, legt den TLPT-Geltungsbereich fest oder zertifiziert das Ergebnis.

## Behördenweiterleitung und -einreichung – nicht implementiert { #authority-routing-and-filing-not-implemented }

Das Repository implementiert keine gerichtsbarkeitsspezifische DORA-Behördenweiterleitung, keine offiziellen Formulare oder Schemata, keine authentifizierte Übertragung, Lieferbelege, Korrekturen, Ablehnungsbearbeitung oder behördliche Annahme. Die Aufzeichnung, dass ein Vorfall gemeldet wurde, ist kein Einreichungsnachweis.
