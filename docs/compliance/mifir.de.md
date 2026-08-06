---
title: MiFIR-Transaktionsmeldung
description: DRAFT_UNVALIDATED MiFIR-förmiger Transaktionsexport-Prototyp; keine RTS 22-Ablageimplementierung.
---

# MiFIR-förmiger Transaktionsexport-Prototyp { #mifir-shaped-transaction-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Instrumentenklassifizierung, Status der meldenden Einheit, Meldefähigkeit, Ausnahmen, Fristen,
    zuständige Behörde, Einreichungsweg, Korrekturpflichten und Aufbewahrung erfordern eine aktuelle
    betreiber-, instrumenten-, transaktions-, handelsplatz-, jurisdiktions- und einsatzspezifische
    Überprüfung durch qualifizierte Rechtsberater und den verantwortlichen Berichtsinhaber. Bei
    dieser Seite handelt es sich weder um eine Rechtsberatung noch um einen Nachweis der
    MiFIR-Konformität.

!!! danger "DRAFT_UNVALIDATED — DO NOT FILE"
    Die aktuelle Ausgabe ist ein unvollständiger, handgefertigter Prototyp. Sie ist nicht anhand eines
    offiziellen RTS 22-Schemas validiert und darf nicht für gesetzliche Einreichungen verwendet werden.
    Generierung, Objektspeicherung, Hashing oder SFTP-Transport bedeuten nicht, dass ein Bericht
    eingereicht, bestätigt, akzeptiert oder rechtlich abgeschlossen wurde.

## Aktuelles Repository-Verhalten { #current-repository-behavior }

`MifirReportingService` wird nach einem Zeitplan ausgeführt und kann über
`POST /api/v1/regulatory-reporting/mifir/generate` ausgelöst werden. Für jede seiner konfigurierten Behördenbezeichnungen geht er wie folgt vor:

1. wählt Handelsausführungszeilen aus, die während des angeforderten Datums für ausgegebene Vermögenswerte in der fest codierten DE/FR-Jurisdiktionsteilmenge erstellt wurden;
2. erstellt ein kleines XML-Dokument, das einen begrenzten Satz von Kennungen, Menge, Preis und Zeitstempelwerten enthält;
3. speichert die generierten Bytes und einen Hash; und
4. ruft das konfigurierte generische Gateway auf.

Jedes generierte Dokument und der zugehörige Nachverfolgungsdatensatz müssen als
`DRAFT_UNVALIDATED` behandelt werden, unabhängig von den Statusnamen der alten Datenbank.

## Fehlende Populationskontrollen { #missing-population-controls }

Der Prototyp erzwingt derzeit nicht:

- eine MiFID-II-/MiFIR-Klassifizierungs- oder Meldepflichtentscheidung auf Instrumentenebene;
- die Kapazität der meldenden Einheit oder des Handelsplatzes;
- den Abwicklungsstatus;
- Transaktionsausnahmen;
- die vom Zielregime geforderte Käufer-/Verkäufer- und Entscheidungsträgeridentifikation;
- die Deduplizierung, Korrektur, Stornierung oder Behandlung verspäteter Meldungen gegenüber früheren Meldungen; oder
- vollständiges Routing zu Jurisdiktion und zuständiger Behörde.

Die Auswahl verwendet `TradeExecution.created_at`; es handelt sich nicht um ein Abwicklungsdatum oder eine unabhängig bestätigte Ausführungspopulation.

## Zielfelder — derzeit nicht implementiert { #target-fields-not-currently-implemented }

Felder wie Käufer- und Verkäuferkennungen, die Identität des meldenden Unternehmens, der MIC-Code des Handelsplatzes, die Kapazität, Entscheidungsträgerdaten, Leerverkaufsindikatoren, Befreiungs-/Warenfelder und weitere RTS-22-Inhalte bleiben Zielanforderungen. Ihre Erwähnung in einem Entwurfsdokument darf nicht als aktuelle Zuordnung gelesen werden.

## XML und Transportgrenze { #xml-and-transport-boundary }

Es gibt keine jurisdiktionsspezifischen `MifirFilingStrategy`-Implementierungen oder behördlich zertifizierten Einreichungsadapter. Der Dienst erzeugt handgefertigtes XML; er belegt keine Schema-, Geschäftsregel-, Referenzdaten- oder Signaturkonformität.

Das generische Gateway kann `NOOP` oder SFTP sein. Ein erfolgreicher SFTP-Upload beweist nur, dass Bytes zu einem konfigurierten Server transportiert wurden. Es ist kein Nachweis für die Zustellung an die zuständige Behörde, die gesetzliche Einreichung, Bestätigung, Validierung oder Annahme. Legacy-Status wie `SUBMITTED`, `PENDING_ACK`, `ACCEPTED` oder `REJECTED` dürfen ohne eine unabhängig authentifizierte und geparste Behördenbestätigung nicht als Behördenergebnisse dargestellt werden.

Automatische Wiederholungsversuche (drei Versuche), die behördenspezifische Erfassung von Bestätigungen, die Korrektur von Ablehnungen und die Benachrichtigung der Aufsichtsbehörde sind nicht implementiert.

## Freigabebedingung { #release-condition }

Die Produktionsnutzung bleibt blockiert, bis der Berichtsperimeter, vollständige Quelldaten, die offizielle Schema- und Geschäftsregelvalidierung, ein behördlich zertifizierter Kanal, ein authentifizierter Empfangs-Lebenszyklus, das Deduplizierungs-/Korrekturmodell, die operative Verantwortlichkeit sowie die rechtliche Freigabe durchgängig implementiert und verifiziert sind.
