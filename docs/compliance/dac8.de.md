---
title: DAC8 / CARF
description: DRAFT_UNVALIDATED-Prototyp für den Bestandsexport; keine Implementierung für DAC8-, CARF- oder KStTG-Meldungen.
---

# DAC8/CARF-förmiger Bestandsexport-Prototyp { #dac8-carf-shaped-holdings-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Der Status der meldenden Einheit, der Umfang, die meldepflichtigen Nutzer und Krypto-Assets, die Sorgfaltspflichten, die Zeiträume, Fristen, die Jurisdiktion, die zuständige Behörde, Korrekturen und die Aufbewahrung erfordern eine aktuelle, betreiber-, kunden-, vermögens-, transaktions-, jurisdiktions- und einsatzspezifische Prüfung durch qualifizierte Steuer-/Rechtsberater und den verantwortlichen Meldeverantwortlichen. Diese Seite stellt keine Rechts- oder Steuerberatung dar und begründet keine Konformität mit DAC8, CARF oder dem deutschen KStTG.

!!! danger "DRAFT_UNVALIDATED — DO NOT FILE"
    Die aktuelle Ausgabe ist ein unvollständiger, handgefertigter Bestandsprototyp. Sie wird nicht anhand eines offiziellen DAC8-, CARF- oder KStTG-Schemas validiert und darf nicht für gesetzliche Einreichungen verwendet werden.
    Generierung, Objektspeicherung, Hashing oder SFTP-Transport bedeuten nicht, dass ein Bericht eingereicht, bestätigt, akzeptiert oder rechtlich vollständig ist.

## Aktuelles Repository-Verhalten { #current-repository-behavior }

`Dac8ExportService` wird am 31. Januar für ein angefordertes Vorjahr ausgeführt und kann bei Bedarf ausgelöst werden.
Für jede der vier konfigurierten Jurisdiktionsbezeichnungen geht er wie folgt vor:

1. fragt aktuell gespeicherte Inhabersalden für ausgegebene Vermögenswerte ab;
2. zählt eingehende Token-Transferzeilen für jeden ausgewählten Inhaber;
3. erstellt ein kleines XML-Dokument unter Verwendung des angeforderten Jahres als Berichtsmetadaten;
4. speichert die generierten Bytes und einen Hash; und
5. ruft das konfigurierte generische Gateway auf.

Die Abfrage rekonstruiert weder einen Snapshot vom 31. Dezember noch jährliche Anschaffungs-/Veräußerungsströme.
Jedes generierte Dokument und zugehörige Nachverfolgungsdatensätze müssen als
`DRAFT_UNVALIDATED` behandelt werden, unabhängig von den Statusnamen der alten Datenbank.

## Fehlende Sorgfaltspflicht- und Populationskontrollen { #missing-due-diligence-and-population-controls }

Der Prototyp implementiert derzeit nicht:

- eine Einstufung als meldende Einheit/CASP oder eine Entscheidung zum Anwendungsbereich des deutschen KStTG;
- eine Sorgfaltspflichtprüfung meldepflichtiger Nutzer und die Klassifizierung beherrschender Personen;
- die vollständige Erhebung von Steueransässigkeit und TIN, Validierung, Begründungscodes oder Selbstzertifizierung;
- die Klassifizierung und Ausschlüsse meldepflichtiger Krypto-Assets;
- die jährliche Aggregation von Bruttoerwerb, -veräußerung, -tausch, -übertragung oder Marktwert;
- eine zuverlässige Momentaufnahme des Jahresendsaldos;
- eine jurisdiktionsspezifische Populationsauswahl oder ein Partnerjurisdiktions-Routing; oder
- die Behandlung von Korrekturen, Stornierungen, Nullmeldungen, Duplikaten und verspäteten Meldungen.

Die gleiche Prototypenpopulation wird derzeit unter mehreren Jurisdiktionsbezeichnungen ausgegeben. Ein
`crossBorderIndicator`, eine vollständige CRS-Partnerbehandlung und die zuvor in dieser Dokumentation beschriebenen meldepflichtigen Benutzer-/Entitätsfelder
sind nicht implementiert.

## Zieldaten — derzeit nicht implementiert { #target-data-not-currently-implemented }

Steuerliche Identität, Ansässigkeit, beherrschende Person, Asset-Klassifizierung, Transaktionsart, Bewertung,
Währung, Jahresaggregation und Jahresend-Felder sind Zielanforderungen für die externe Analyse; ihr Vorhandensein
in einer Entwurfstabelle darf nicht als aktuelles Source-Mapping dargestellt werden.

## XML und Transportgrenze { #xml-and-transport-boundary }

Der Dienst erzeugt handgefertigtes XML und stellt keine Konformität mit einem offiziellen OECD-, EU- oder
deutschen Schema oder Geschäftsregeln her. Es gibt keine autoritätsspezifischen Portaladapter oder authentifizierten
Empfangsprozessoren.

Das generische Gateway kann `NOOP` oder SFTP sein. Ein erfolgreicher SFTP-Upload beweist nur, dass Bytes zu einem konfigurierten Server transportiert wurden. Es stellt keinen Beweis für die Lieferung an eine Steuerbehörde, die gesetzlich vorgeschriebene Einreichung, Bestätigung, Validierung oder Annahme dar. Legacy-Status wie `SUBMITTED`,
`PENDING_ACK`, `ACCEPTED` oder `REJECTED` dürfen ohne einen unabhängig authentifizierten und geparsten Autoritätsempfang nicht als Autoritätsergebnisse dargestellt werden.

## Timing und geltendes Recht { #timing-and-current-law }

Verlassen Sie sich nicht auf historische Aussagen, dass das erste Berichtsjahr 2025 war oder dass die Portale der Mitgliedstaaten 2025 noch im Aufbau waren. Anwendbare Zeiträume, deutsche KStTG-Anforderungen,
Einreichungstermine, Schemata, Portale und Übergangsregeln müssen bei der externen Prüfung anhand aktueller amtlicher
Quellen überprüft werden.

## Zusammenhang mit MiFIR { #relationship-to-mifir }

MiFIR-Transaktionsberichterstattung und DAC8/CARF/KStTG-Steuerberichte haben unterschiedliche rechtliche Rahmenbedingungen,
Populationen, Daten, Empfänger, Zeiträume und Korrekturprozesse. Die gemeinsame Nutzung einer Persistenztabelle oder
Transportschnittstelle zeigt nicht die Konformität eines der beiden Prototypen mit seinem Zielregime.

## Freigabebedingung { #release-condition }

Die Produktionsnutzung bleibt blockiert, bis der Berichtsumfang, das Sorgfaltspflichtmodell, die vollständigen
Quelldaten und historischen Snapshots, die Validierung anhand des offiziellen Schemas und der Geschäftsregeln,
ein behördlich zertifizierter Kanal, ein authentifizierter Empfangs-Lebenszyklus, das Korrekturmodell, die
operative Verantwortlichkeit sowie die rechtliche/steuerliche Freigabe durchgängig implementiert und verifiziert sind.
