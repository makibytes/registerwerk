---
title: Registerwerk Prüfungsbefunde-Register
description: Der vorgeschlagene Kontrollnachweis für eine künftige multidisziplinäre Überprüfung von Registerwerk — kein Nachweis dafür, dass eine Überprüfung stattgefunden hat.
---

# Registerwerk Prüfungsbefunde-Register

Letzte Aktualisierung: 2026-07-29

> **Keine der in diesem Dokument beschriebenen Überprüfungen hat stattgefunden.** Es wurde kein Fachgremium und kein IT-Board
> einberufen, benannt oder konsultiert. Jeder Eintrag unten wurde von einem automatisierten Mitwirkenden als *vorgeschlagene*
> Prüfstruktur und Selbsteinschätzung des Repositorys verfasst. Lesen Sie dies als Plan für eine künftige Überprüfung, niemals
> als Nachweis dafür, dass eine solche stattgefunden hat. Eine abgeschlossene Codeänderung ist keine rechtliche Zertifizierung.
> Punkte, die von Instrumentenbedingungen, einer Betreiberlizenz, externen Nachweisen, der Einsatzkonfiguration oder
> qualifizierter anwaltlicher Beratung abhängen, bleiben unentschieden.

Dieses Dokument schlägt den Kontrollnachweis für eine künftige multidisziplinäre Überprüfung von Registerwerk vor: was
überprüft würde, von wem, und welche Nachweise jedes Urteil erfordern würde.

## Vorgeschlagenes Entscheidungsprotokoll

Die folgenden Gremien werden vorgeschlagen, sind jedoch nicht besetzt. Fachgremien würden Anleiheemission und -abwicklung,
Zahlungen, Finanzkriminalität und regulatorische Compliance, Krypto-Assets und Handel, Prüfung sowie Repo/Kreditvergabe
abdecken. Ein IT-Board würde Softwaredesign und -implementierung, Architektur, SRE, Frontend und Kryptografie abdecken.

Im Rahmen des Vorschlags würde das IT-Board Vorschläge auf einer Skala von 0 bis 2 in den Dimensionen Treue zu rechtlichen
Invarianten, Korrektheit des Registers, Architektur, Sicherheit/Datenschutz, Datenlebenszyklus, UX/Barrierefreiheit,
Betreibbarkeit und Verifizierung bewerten. Ein Vorschlag wäre:

- genehmigt bei 14–16 Punkten ohne eine Null in den ersten fünf Dimensionen;
- genehmigt mit Änderungen bei 9–13 Punkten;
- verworfen bei 0–8 Punkten.

Das Board könnte ein Veto einlegen gegen mandantenübergreifenden Zugriff, unsichere Schlüssel, Geldbeträge als
Gleitkommazahlen, nicht-idempotente Abwicklung, irreversible Migrationen, geschwächte Doppelkontrolle, unbegrenzte
Eingaben, fehlende Finalitäts-/Reorg-Behandlung, nicht beobachtbaren Abgleich oder eine rechtliche Invariante ohne
Abnahmekriterien.

Für dieses Register vorgeschlagene Status sind `PENDING`, `BLOCKED_DECISION`, `APPROVED`, `IN_PROGRESS`, `VERIFIED`,
`DISMISSED` und `RESIDUAL_RISK`. Keiner davon wurde von einem Prüfer vergeben.

## Prüfungsabdeckung

Jede Zelle lautet `Not performed`. Die Spalte „Umfang" hält fest, was eine Überprüfung *abdecken würde*. Die automatisierte
Selbsteinschätzung wird separat in `docs/claims/registry.json` erfasst, wo sie den Status `SELF_ASSESSED_UNREVIEWED` trägt.

| Phase | Bestandteile | Fachliche Überprüfung | IT-Überprüfung | Umsetzung |
|---|---|---|---|---|
| Inventar | Spring-Backend und 31 Fachmodule; EVM-, Cairo- und DAML-Verträge; EVM-/Solana-/Canton-/Starknet-/Stellar-Indexer; Angular-Apps für Betreiber und Anleger nebst gemeinsamer UI; vertraulicher Token-Relayer; Kong, Compose, Helm und Monitoring; Dokumentation | Nicht durchgeführt | Nicht durchgeführt | Nur Baseline |
| 0 — Invarianten | Akteurs-/Kapazitätsmodell, Instrumentenperimeter, Registerautorität, Vermögens- und Geldeinheiten, Finalität, Claims, Freigabetore | Nicht durchgeführt | Nicht durchgeführt | Teilweise, nur selbst eingeschätzt |
| 1 — Autorität und Compliance | Authentifizierung, Autorisierung, Rechtsträger, KYC/AML, Screening, Travel Rule, Jurisdiktionsfreigaben, Audit, Datenschutz, zentrales Betriebstor | Ausstehend | Ausstehend | Ausstehend |
| 2 — Emission und Abwicklung | Asset-Lebenszyklus/Deployment, Token-Standards, Identitäts-/Compliance-Verträge, Register, Zahlungen, LgZ, Verwahrung, Indexer, Kapitalmaßnahmen | Ausstehend | Ausstehend | Ausstehend |
| 3 — Märkte und Meldewesen | Marketplace/Trading, Repo/Kreditvergabe, Oracle und NAV, Anleiheservicing, MiFIR, DAC8/KStTG, DORA und Jurisdiktionsprofile | Ausstehend | Ausstehend | Ausstehend |
| 4 — Benutzeroberflächen | Operator-UI, Investor-UI, gemeinsame UI, API-Verträge, Barrierefreiheit und sichere Transaktionsdarstellung | Ausstehend | Ausstehend | Ausstehend |
| 5 — Betrieb | CI, Abhängigkeiten, Container, Kong, Helm, Secrets, Netzwerkrichtlinien, Monitoring, Backup/Restore, SLOs und Runbooks | Ausstehend | Ausstehend | Ausstehend |
| Abschluss | Vollständige Tests, Replay-/Abgleichsnachweise, Claim-Abgleich, Migrationshinweise und Freigabe des Restrisikos | Ausstehend | Ausstehend | Ausstehend |

## Kanonisches Modell für Phase 0

### Autorität und Finalität

Jedes Instrument muss über eine versionierte Perimeterentscheidung verfügen, die das rechtliche Register, das technische
Ledger, die Projektionsrichtung, die registerführende Stelle und die für die Rechtswirksamkeit erforderlichen Nachweise
benennt. Die Wahl des Token-Standards darf das Instrument nicht klassifizieren.

Das System darf diese Dimensionen nicht in ein einziges `SETTLED`-Flag komprimieren:

`INITIATED → EXECUTED → TECHNICALLY_FINAL → CASH_CONFIRMED → REGISTER_POSTED → RECONCILED → LEGALLY_EFFECTIVE`

Für deutsche eWpG-Instrumente ist der aktuelle Datenbank-Inhaberdatensatz nur das behauptete Rechtsregister, bis eine
instrumentenspezifische, anwaltlich freigegebene Autoritätsrichtlinie vorliegt. Eine Chain-Transaktion allein darf nicht
als rechtliche Ummeldung bezeichnet werden. Luxemburg, Frankreich und Liechtenstein benötigen eigene
Instrumentenentscheidungen, statt das deutsche Modell zu übernehmen. Grundlage der deutschen Unterscheidung ist das
aktuell amtliche [eWpG](https://www.gesetze-im-internet.de/ewpg/BJNR142310021.html); die maßgeblichen Produkt- und
Betreiberentscheidungen für Frankreich und Luxemburg müssen anhand der [AMF-DLT-Pilotleitlinien](https://www.amf-france.org/en/news-publications/depth/pilot-regime)
und des [luxemburgischen Rahmens für entmaterialisierte Wertpapiere](https://www.cssf.lu/en/Document/law-of-6-april-2013/)
geprüft werden.

### Einheitenkonventionen

| Wert | Kanonische Konvention |
|---|---|
| Registrierte Menge | Wertpapiereinheiten mit explizitem `quantityScale`; die Umrechnung in Chain-Basiseinheiten erfordert deklarierte `tokenDecimals` |
| Währung | ISO-4217-Haupteinheiten in Backend-Modellen plus expliziter Währungsexponent und Rundung |
| Anleihe-Nennbetrag | Hauptwährungseinheiten je ganzer Wertpapiereinheit |
| Ausgabepreis | Dimensionsloser Bruchteil des Nennbetrags; `1.00` bedeutet 100 % |
| Fester Kupon | Jährlicher Dezimalsatz; der Kupon ergibt sich aus Kapital × Jahreszins × vertraglichem Zinsberechnungs-Bruchteil, gerundet pro Zahlungsempfänger |
| Handelspreis | Hauptwährungseinheiten je ganzer Wertpapiereinheit mit expliziter Währung |
| Token-Zahlung | Exakte Token-Basiseinheiten nach verifizierter Dezimalkonvertierung |
| ERC-4626-NAV | WAD-Festkomma; `1e18` bedeutet eine zugrunde liegende Basiseinheit je Anteils-Basiseinheit |
| Repo-Preis | Kredit-Token-Basiseinheiten je ganzem Zero-Decimal-Sicherheiten-Token |
| Repo-Satz/-Index | WAD; LTV, Reservefaktor und Verwertungsabschlag in Basispunkten |
| Zeit | Gesetzlicher Kalender/Zeitzone für Vertragstermine; UTC-Zeitpunkt und kanonischer Block-Nachweis für Chain-Ereignisse |

### Claim-Baseline

| Claim | Befund | Erforderliche Disposition |
|---|---|---|
| „Vollständig konform" in DE/LU/FR/LI | Falsch als unbedingte Behauptung | Ersetzen durch bereichsbezogene, nachgewiesene, befristete Entscheidungen je Instrument und Betreiber |
| Jeder Emittent/Empfänger durchläuft KYC vor wertrelevanten Aktionen | Falsch | Zentrales serverseitiges Betriebstor plus geprüfte Nachweise zu Dokumenten, wirtschaftlich Berechtigten und Screening |
| Datenbank oder Blockchain ist universell maßgeblich | Widersprüchliche Dokumentation | Autorität je Instrument auswählen; Rechtsregister von technischem Ledger und Projektion unterscheiden |
| MiFIR-Meldewesen ist produktionsreif | Platzhalter | Ausgabe unter Quarantäne stellen, bis Population, RTS-22-Schema, Korrektur/Deduplizierung und Bestätigungsverarbeitung vorliegen |
| DAC8-Export ist einsatzbereit | Falsch/veraltet für die aktuelle deutsche Implementierung | Neuaufbau um Sorgfaltspflichten für meldepflichtige Nutzer, Steueransässigkeit/TIN, Ströme, Jurisdiktions-Routing, Korrekturen und KStTG-Entscheidungen |
| MiCAR-konforme Zahlungswege | Falsch | Als Betreiberbescheinigungen behandeln, bis Emittenten-, Klassifizierungs-, Autorisierungs- und Einlösungsnachweise verifiziert sind |
| DORA-Vorfallsautomatisierung | Platzhalter | Manuelle Aufzeichnungen als solche kennzeichnen und beibehalten; Erkennung, Klassifizierung, Weiterleitung und Übermittlungsnachweis implementieren, bevor Automatisierung behauptet wird |
| PII ist ruhend verschlüsselt | Falsch für Spalten mit natürlichen Personen | Den Claim korrigieren oder Feld-/Anwendungsverschlüsselung mit Schlüssel-Lebenszyklus und Migration implementieren |
| Alle Chains/Standards sind implementiert | Falsch | Starknet/Stellar und jede andere Skelett-Integration müssen als Platzhalter gekennzeichnet werden |
| Same-Chain-LgZ ist atomar | Nur für Exact-Transfer-Token und eine Transaktion verifiziert | Exakte-Leg-Prüfungen, Finalitäts-/Reorg-Nachweise und Rechtsregister-Abgleich ergänzen |

## Vorschlagsregister Phase 0 (selbst eingeschätzt, nicht überprüft)

| ID | Vorschlag | Selbsteinschätzung | Tracking-Status | Erfasste Nachweise / Blocker |
|---|---|---|---|---|
| M0-3525-A | Address-Form-ERC-3525-Übertragung korrigieren, sodass Quelle genau einmal abnimmt und Ziel genau einmal zunimmt | Vorgeschlagen (nicht überprüft) | SELF_ASSESSED | Nur Nachweis der Vertragserhaltung: Regressionstests plus vollständige Foundry-Suite, 449 bestanden / 31 übersprungen; dies belegt keinen indexierten oder Rechtsregister-Abgleich |
| M0-3525-B | Pause-/Freeze-/Whitelist-Richtlinie auf Ganztoken-Eigentumsübertragungen anwenden | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | IN_PROGRESS | Jede Schutzmaßnahme über den ERC-721-Ownership-Hook erzwingen, Zero-Address-Mint/Burn-Semantik und Bypass für erzwungene Operationen erhalten, beide Transfer-APIs sowie atomares Address-Form-Scheitern testen |
| M0-3525-C | Globale und Slot-Obergrenzen mit expliziter kumulativer vs. ausstehender Semantik durchsetzen | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Kumulative vs. ausstehende Semantik, Burn-/Redemption-/Forced-Burn-Spielraum, Cap-Hierarchie, Änderungs- und Absenkungsverhalten entscheiden; Alt-Emission/Ausstehendes je Slot abgleichen |
| M0-7540-A | Geerbte synchrone `deposit`, `mint`, `withdraw` und `redeem` deaktivieren; Nullmaxima ausweisen | Vorgeschlagen (nicht überprüft) | SELF_ASSESSED | Alle synchronen Routen reverten, Maxima sind null, Request-Tests bestehen; vollständige Foundry-Suite endet mit 0 |
| M0-7540-B | Erfüllung an unveränderliche, zeitnahe NAV-Strike-Metadaten binden | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Forward-/historische Preisbildung, Cutoff-Kalender/Zeitzone, Maximalalter, zulässigen Strike, Korrektur/Ersetzung und Bewertungsautorität entscheiden; Alt-Requests bleiben `UNVERIFIED_STRIKE` |
| M0-4626 | NAV-Metadaten/-Aktualität und Reserve-Solvenzmodell durchsetzen | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Cash-hinterlegtes synchrones vs. verwaltetes Portfolio-Async-Modell, zulässige Reserven/Verwahrung, Liquiditätspuffer, Gebühren und Rücknahmeform entscheiden |
| M0-REPO-A | Ceiling-gerundete skalierte Anteile bei Asset-Entnahme verbrennen und Nullwert-Anteilsbewegungen zurückweisen | Vorgeschlagen (nicht überprüft) | SELF_ASSESSED | Grenzwerttest oberhalb des 1e18-Index plus Repo-Invarianten: 3 bestanden bei je 256 Läufen / 5.120 Aufrufen |
| M0-REPO-B | Verhindern, dass Entfernen/Wieder-Hinzufügen einen Markt mehr als einmal bewertet | Vorgeschlagen (nicht überprüft) | SELF_ASSESSED | Regressionstest für Wieder-Hinzufügen und vollständiger Foundry-Suite-Durchlauf bestehen; `marketCount` bleibt jetzt eindeutig |
| M0-REPO-C | Supply, Kreditaufnahme, Rückzahlung, Verwertung und vollständige Exits anteilskonservativ und überlaufsicher gestalten | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Überlaufsicheres `mulDiv` verwenden, Null-Rechnungseinheiten zurückweisen, Schulden konservativ erfassen, teilweise Bargeld-/Sicherheitenbewegung auf tatsächlichen Schulden-Deltas basieren und vollständige Exits explizit machen; unveränderliche Live-Märkte benötigen weiterhin Bestands-/Abwicklungs-/Ersatznachweise |
| M0-REPO-RISK | Oracle-Kadenz/Override, LLTV-/Bonus-Verhältnis, Close-Factor und Ausfall-Wasserfall | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Oracle-/Kadenz-/Override-Quorum, LLTV-/Bonus-Verhältnis, Close-Factor/Stale-Regel, Verlust-Wasserfall und rechtliche/Verwahrungsbedingungen der Sicherheiten entscheiden; diese nicht im Arithmetik-Batch ändern |
| M0-DVP | Exact-Transfer-Leg-Verifizierung, laufzeitgebundene Trade-IDs und Backend-Finalitätsstatus | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Nur technischer Batch: Kontosaldo-Deltas beider Konten, domänengetrennte Term-/Salt-ID und vorläufiger Ereignis-/Belegs-Lebenszyklus; Stornorechte, Chain-Finalitätsschwelle und rechtlicher Abwicklungsweg bleiben Produktentscheidungen |
| M0-BOND | Dezimalstellen, Fälligkeit, Nachweisstichtag-Ansprüche und mengenbasierte Rückzahlung normalisieren | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Zinsberechnungsmethode, Geschäftskalender/Zeitzone, Nachweisstichtag-/Ex-Tag-Autorität, Rundung, Quellensteuer/Sperrkonto, Ausfall/Kündigung/Änderung und Teilrückzahlungsbedingungen entscheiden; aktuellen Desk als reine Referenz unter Quarantäne stellen |
| M0-LEDGER | Abwicklungsübergänge monoton machen, Bestand genau einmal wiederherstellen und unabhängigen Bargeld-/Liefernachweis verlangen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Additives Status-/Übergangs-/Nachweis-/Reservierungsmodell; das alte `SETTLED` wird unverifiziert, Käuferreferenzen können den Status nicht befördern, und `LEGALLY_EFFECTIVE` bleibt ohne konfigurierte Autoritätsrichtlinie unerreichbar |
| M0-INDEXER-A | Konfigurierte Handler-Signatur-Parität, Factory-Deployment-Ereignisse und komponentenweise Adressdarstellung reparieren | Vorgeschlagen (nicht überprüft) | SELF_ASSESSED | Begrenztes technisches Ergebnis: 16 Contract-ABIs / 71 konfigurierte Handler, Address-Renderer, Codegen, WASM-Build und reiner Validierungs-Wrapper bestehen; dies belegt nicht die Identität des bereitgestellten Codes |
| M0-INDEXER-B | Vorläufige/finale Cursor, Reorg-Rollback und direkten Chain-Abgleich ergänzen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Fail-Closed-Infrastruktur für vorläufig/verwaist/Rewind und Checkpoint-Abgleich aufbauen; kein Ereignis wird `FINAL`, bis eine separat genehmigte Chain-Richtlinie und vertrauenswürdige RPC-Konfiguration vorliegen |
| M0-INDEXER-C | ERC-3525-Wert nach Token/Eigentümer/Slot, dauerhaften ERC-7540-Request-Lebenszyklus einschließlich Stornierung und Repo-Skalierungs-/Vault-Cashflow-Status verfolgen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | SELF_ASSESSED | Alle 25 Entitäten haben einen Enum-Projektionsstatus; erstmalig beobachtete unvollständige Historien bleiben `INCOMPLETE`; RepoVault ist vorzeichenbehafteter Netto-Vermögens-Cashflow, kein Kapitalbetrag; vollständiges statisches Gate besteht. Es existiert kein Replay-/Finalitätsnachweis |
| M0-INDEXER-D1 | Jede konfigurierte BondDesk-/AMM-/RepoVault-Instanz unterstützen, Betreiber-Migrationsdokumente aktualisieren und das Test-Gate Mappings kompilieren lassen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | SELF_ASSESSED | Alle Instanzen sind explizit; `NONE` ist eine Betreiberbehauptung; Live-Deployment erfordert ein neues Label; Graph-Node-Reload geht dem Deployment voraus; quellenweise Blöcke und zerstörungsfreies Rollback sind dokumentiert und gegengeprüft |
| M0-INDEXER-D2 | RPC-Bytecode und genehmigten Laufzeitcode-Hash/Komponentenidentität vor Deployment verifizieren | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Erfordert maßgebliches Inventar je Chain, genehmigte Artefakt-/Laufzeit-/Proxy-/Admin-Hashes, Schlüsselerwartungen und Rotationsrichtlinie; syntaktische Adressprüfungen sind keine Identitätsverifizierung |
| F0-001 | Versionierter Instrumentenperimeter, rechtliche Kapazitäten, regulatorische Zulassungen und Ledger-Autoritätsrichtlinie | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Entscheidungen von Rechtsberatung/Betreiber je Jurisdiktion und Instrument; F0-002 kann eine Schema-Hülle ergänzen, darf aber keine aktive Pauschalfreigabe säen |
| F0-002 | Zentrales `AssetOperationGate` in Diensten und HTTP-Pfaden erzwungen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Versionierte, bereichsbezogene, befristete/widerrufliche Entscheidungs-Snapshots auf Service-Ebene; fehlende/veraltete/nicht erkannte Richtlinie verweigert ohne DB-/Chain-Nebenwirkung und protokolliert Richtlinie/Grund/Audit-Korrelation |
| F0-003 | KYC-Nachweise aus geprüftem Dokument, wirtschaftlich Berechtigtem, Jurisdiktion und aktuellem Screening | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Checklisten, Prüfung/Annahme, Kadenz, EDD, Vollständigkeit/Quelle des wirtschaftlich Berechtigten und Aufbewahrung entscheiden; hochgeladene Altdokumente bleiben ungeprüft, und das Betriebstor verweigert |
| F0-004 | Explizite unveränderliche Wirtschaftsbedingungen, Skalen, Währungen, Kalender und Rundung | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Nur unveränderliches/versioniertes Schema und exaktes Konvertierungs-/Berechnungsframework aufbauen; aktuelle Bedingungen als `LEGACY_UNVERIFIED` migrieren und keine Anleihe-/NAV-Konventionen erfinden |
| F0-005 | Mehrdimensionales Abwicklungsstatus- und Nachweismodell | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | PROPOSED | Gleiche sichere Grenze wie M0-LEDGER; `LEGALLY_EFFECTIVE` bleibt ohne F0-001 unerreichbar, und das alte `SETTLED` wird zu `LEGACY_SETTLED_UNVERIFIED` |
| F0-006 | Autorisierte Anweisung/Vereinbarung und chronologisches Registeränderungsbuch | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Autorität für Anweisung/Vereinbarung/Korrektur, Signaturen/Nachweise, Reihenfolge und Rückabwicklung je Eintragsart/Jurisdiktion entscheiden; eine generische Nur-Anhängen-Historie kann keine Mutation autorisieren |
| F0-007 | Chain-Finalität und Abgleich von bereitgestelltem Bytecode/Admin/Konfiguration | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | M0-INDEXER-B kann vorläufige Infrastruktur ergänzen, aber Finalitäts-/Checkpoint-, vertrauenswürdige RPC-/Quorum-, Laufzeit-/Proxy-/Admin-/Owner-/Schlüssel- und Rechtsvertrauensrichtlinien sind ungeklärt |
| F0-008 | Verifizierbare Zahlungs-/LgZ-Abwicklung; simulierte kanonische Mutationen in Produktion deaktivieren | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | IN_PROGRESS | Einstellungen/Schema standardmäßig auf initial und sofortige Abwicklung auf falsch setzen; Parteireferenzen sind unverifizierte Metadaten; exakte LgZ-Legs mit unabhängigem Adapternachweis kombinieren und keine Inhaber-Mutation ohne verifizierte Zahlung und Lieferung |
| F0-009 | Gesperrter Anspruchs-Snapshot und unabhängig verifizierte Kapitalmaßnahmen-Zahlungen | Blockiert — Entscheidung erforderlich | BLOCKED_DECISION | Nachweisstichtag-/Ex-Tag-Autorität, Zeitzone/Kalender, Steuern/Quellensteuer, Sperrkonto für gesperrte Inhaber, Korrekturen und ausgefallene Zahlungen entscheiden; Alt-Ansprüche bleiben unverifiziert |
| F0-010 | Notabschaltung für die Kreditvergabe, bis rechtliche/Sicherheiten-Kontrollen und Abgleich vorliegen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | IN_PROGRESS | Backend-/UI-Exposition standardmäßig deaktivieren und fail-closed; neue Märkte pausieren Supply und Kreditaufnahme standardmäßig, während risikoreduzierende Entnahme/Rückzahlung verfügbar bleiben; alte Märkte benötigen Bestands-/Pause-/Abwicklungs-/Ersatznachweise |
| F0-011 | MiFIR- und DAC8/KStTG-Ausgaben als Entwurf/nicht validiert unter Quarantäne stellen | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | SELF_ASSESSED | Standardmäßig deaktiviert und in Produktion bei Aktivierung untersagt; Prototyp-Namespaces und `DRAFT_UNVALIDATED`; nur transportbezogene Status/Ereignisse; 20 gezielte Unit-/Migrationstests bestehen, einschließlich seeded PostgreSQL V17→V18. Offizielle Schemata, Population, Routing, Bestätigungen und rechtliche Freigabe bleiben Blocker |
| F0-012 | Maschinenlesbares Claims-Register mit Nachweis, Umfang, Owner, Ablauf und CI-Durchsetzung | Vorgeschlagen, Änderungen vermerkt (nicht überprüft) | SELF_ASSESSED | Geschlossenes Schema/Validator, kanonischer Datensatz und exakte Text-/Datei-Hashes, Nur-Anhängen-Basisvergleich, Ablauf-/Unabhängigkeitsprüfungen, eine einzige zugelassene unveränderliche Migrationsausnahme, Fail-Closed-Repository-Scan und verpflichtender Gating-CI-Nachweis wurden von einem automatisierten Mitwirkenden ohne externe Überprüfung selbst eingeschätzt. Aktueller Re-Run: Verifizierer/Regressionen, ERC-3525 (17/17), Backend-Reporting (20/20 einschließlich PostgreSQL-Migration) und vollständige Subgraph-Static-/Codegen-/WASM-Gates bestehen. Dies ist Governance, keine rechtliche Zertifizierung |

## Baseline-Nachweise

| Bereich | Baseline-Ergebnis | Befund |
|---|---|---|
| Backend `./mvnw verify -B` | Baseline außerhalb der eingeschränkten Sandbox bestanden; F0-011-kombinierte gezielte Unit-/Migrationssuite besteht 20/20 | Geplante Jobs laufen nach dem Herunterfahren der Testanwendung weiter, erzeugen umfangreiche Datenbankfehler und verzögern das Herunterfahren des Forks; tatsächlicher JaCoCo-Wert liegt bei ca. 45,0 % Line / 38,6 % Branch gegenüber einem Gate von 36 % / 23 % und widersprüchlicher 70-%-Dokumentation |
| Foundry `forge test -q` | 449 bestanden, 31 übersprungen nach dem ersten genehmigten Batch; unabhängiger Re-Run mit Exit-Code 0 | Regressionstests decken jetzt ERC-3525-Address-Transfer-Erhaltung, ERC-7540-synchronen Bypass, Repo-Withdrawal-Rundung und eindeutige Marktbewertung ab |
| Cairo `snforge test` | 29/29 bestanden | Cairo-Oberfläche benötigt weiterhin fachliche/Sicherheitsüberprüfung |
| Vertraulicher Relayer | TypeScript-6-Lint/Build und 33/33 Vitest-Tests bestanden | Express-5-/ESM-Migration abgeschlossen; Abhängigkeitsprüfung meldet keine Befunde |
| EVM-Subgraph | 16 ABI-Contracts / 71 Handler, 25 Projektionsstatus-Entitäten, Multi-Instanz-Renderer, Codegen und alle Mapping-Builds bestehen | Produktionsprüfung ist sauber; ein ausschließlich vorgelagerter Graph-CLI-`decompress`-Pfad wird durch die ausführbare Allowlist in `SECURITY-EXCEPTIONS.md` isoliert |
| Operator-/Investor-Angular-Apps | Angular-22-Lint/Build bestanden; 124 Operator- und 125 Kunden-Vitest-Tests bestehen | Native zonenlose Laufzeit und Angular-Build/Vitest ersetzen Karma |
| MkDocs-Dokumentation | Strikter Fünf-Sprachen-Image-Build und Browser-Tests bestehen | Mermaid, Theme-Umschaltung und Sprachenwechsel mit Origin/Port sind abgedeckt; Produktionsprüfung ist sauber |
| DAML | Nicht ausgeführt | `dpm` ist in der aktuellen Umgebung nicht verfügbar |

## Bekannte Blocker bei Deployment und Betrieb

- Helm kombiniert ein einzelnes `ReadWriteOnce`-Wallet-Volume mit 3–10 anti-affinen Replikaten.
- Der Ingress leitet direkt an das Backend weiter und umgeht Kong, während die Netzwerkrichtlinie den Ingress-Controller-Pfad nicht zulässt.
- Die von Helm referenzierten PostgreSQL-Secret-Keys stimmen nicht überein.
- Frontend-JWTs werden in `localStorage` gespeichert; Response-Hardening-Header sind unvollständig.
- Promtail, Kong-Metriken, Backup-Alarme und Pushgateway-Annahmen ergeben zusammen keinen funktionierenden Monitoring-Pfad.
- Ein einzelner roher Deployment-Key hat keine dokumentierte Multisig-/Timelock-Übergabe.
- Es gibt keine CI-Abdeckung für gemeinsam genutzten Frontend-Code, den Relayer, Cairo, DAML, mehrere Indexer, Dokumentation, Compose/Kong oder Helm.

Diese bleiben Release-Blocker, bis ihr Phasenurteil und ihre Verifizierungsnachweise hier erfasst sind.
