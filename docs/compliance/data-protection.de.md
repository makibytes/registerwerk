---
title: Datenschutz (DSGVO / GDPR)
description: Bestandsaufnahme personenbezogener Daten und teilweise DSAR-Workflows, mit aktuellen Verschlüsselungs- und Abdeckungslücken.
---

# Datenschutz (DSGVO / GDPR) { #data-protection-dsgvo-gdpr }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Diese Seite dokumentiert beabsichtigte Datenschutzkontroll-Zuordnungen und das aktuelle
    Repository-Verhalten. Sie ist keine GDPR/DSGVO-Compliance-Bewertung, keine genehmigte ROPA,
    DPIA, Aufbewahrungsentscheidung oder rechtliche Rechtsgrundlagenfeststellung. Die Rollen des
    Verantwortlichen/Auftragsverarbeiters, die Zwecke, die Rechtsgrundlagen, der Datenbestand, die
    Aufbewahrung, die Bearbeitung von Betroffenenrechten und die Sicherheitsmaßnahmen erfordern
    eine einsatzspezifische Überprüfung durch den Verantwortlichen, den DPO, die
    Sicherheitsverantwortlichen und qualifizierte Rechtsberater.

Die **Verordnung (EU) 2016/679** (DSGVO, englisch GDPR) gilt für alle personenbezogenen Daten, die von Registerwerk-Betreibern verarbeitet werden. Als Wertpapierregister, das Namen, Geburtsdaten, Steuernummern, Passnummern und Finanzdaten natürlicher Personen verarbeitet, ist Registerwerk ein Verantwortlicher (und mitunter auch Auftragsverarbeiter), der den vollen Pflichten der DSGVO unterliegt.

---

## Personenbezogene Daten in Registerwerk { #personal-data-in-registerwerk }

Der primäre Ort für personenbezogene Daten ist die `NaturalPerson`-Entität. Dazu gehören:

| Feld | DSGVO-Kategorie | Zweck |
|---|---|---|
| `givenName`, `familyName` | Personenbezogene Daten | KYC-Identitätsprüfung |
| `dateOfBirth` | Personenbezogene Daten | KYC-Identitätsprüfung |
| `nationality`, `countryOfResidence` | Personenbezogene Daten | Sanktionsprüfung, Berichterstattung |
| `taxId`, `taxIdCountry` | Sensible personenbezogene Daten | DAC8/CARF-Meldung |
| `address`-Felder | Personenbezogene Daten | KYC-Prüfung, Dokumentenkorrespondenz |
| `pepStatus` | Besondere Kategorie (politisch) | Verstärkte Sorgfaltspflichten |
| Dokumentendateien (Pässe, Personalausweise) | Sensible personenbezogene Daten | KYC-Prüfung — gespeichert in S3 |

---

## Verschlüsselung im Ruhezustand — nicht implementiert für `NaturalPerson`-Felder { #encryption-at-rest-not-implemented-for-naturalperson-fields }

`NaturalPerson`-PII ist derzeit gewöhnlichen Datenbankspalten zugeordnet. Das Repository implementiert für diese Felder keine anwendungsseitige Spaltenverschlüsselung, keine DEKs pro Datensatz, kein KEK-Wrapping und keine kryptografische Löschung. Die Verschlüsselung des Datenbank-Volumes und des Objektspeichers kann extern konfiguriert werden, muss jedoch bei jeder Bereitstellung überprüft werden und ersetzt nicht die anwendungsseitigen Kontrollen, wo diese erforderlich sind.

---

## Art. 30 — Verzeichnis von Verarbeitungstätigkeiten (ROPA) { #art-30-records-of-processing-activities-ropa }

Das Repository enthält einen ROPA-Entwurf und ein erstes Verzeichnis der Verarbeitungstätigkeiten. Vollständigkeit, Rechtsgrundlagen, Aufbewahrungsfristen, Zuständigkeit und Genehmigung werden durch das Repository nicht festgelegt:

| Tätigkeit | Rechtsgrundlage | Aufbewahrung |
|---|---|---|
| KYC/KYB-Identitätsprüfung | Gesetzliche Verpflichtung (GwG, TVTG, AMF) | Je nach Gerichtsbarkeit (5–10 Jahre) |
| Sanktionsprüfung | Gesetzliche Verpflichtung | Je nach Gerichtsbarkeit |
| Wertpapierregistereinträge | Gesetzliche Verpflichtung (eWpG, TVTG) | Je nach Gerichtsbarkeit (5–10 Jahre) |
| Transaktionsmeldung (MiFIR) | Gesetzliche Verpflichtung | Gemäß MiFIR-Aufbewahrungsregeln |
| DAC8-Steuermeldung | Gesetzliche Verpflichtung | Regeln je Mitgliedstaat |
| Kommunikation mit dem Kundensupport | Berechtigtes Interesse | 3 Jahre nach dem letzten Kontakt |
| Audit-Protokoll | Gesetzliche Verpflichtung | Je nach Gerichtsbarkeit |

Der Entwurf wird unter `docs/compliance/ropa.md` gespeichert. Eine Bereitstellung muss einen Eigentümer benennen, ihn vervollständigen und genehmigen, Prüfnachweise dokumentieren und einen Überprüfungsrhythmus festlegen.

---

## Art. 35 — Datenschutz-Folgenabschätzung (DPIA) { #art-35-data-protection-impact-assessment-dpia }

Das Repository enthält DPIA-Entwürfe je Gerichtsbarkeit. Ob eine DPIA erforderlich ist und ob ein Entwurf vollständig und genehmigt ist, muss für den jeweiligen Einsatz festgestellt werden:

- `docs/compliance/dpia-DE.md` — Deutscher eWpG-Einsatz
- `docs/compliance/dpia-LU.md` — Luxemburgischer CSSF-Einsatz
- `docs/compliance/dpia-FR.md` — Französischer AMF-Einsatz
- `docs/compliance/dpia-LI.md` — Liechtensteinischer TVTG-Einsatz

Diese Dateien sind Prüfungsgrundlagen, kein Nachweis für eine genehmigte DPIA.

---

## Art. 17 — Recht auf Löschung („Recht auf Vergessenwerden") { #art-17-right-to-erasure-right-to-be-forgotten }

Art. 17 DSGVO gibt betroffenen Personen das Recht, die Löschung ihrer personenbezogenen Daten zu verlangen. Art. 17 Abs. 3 lit. b sieht jedoch eine Ausnahme für Daten vor, die zur Erfüllung einer gesetzlichen Verpflichtung aufbewahrt werden. Für Registerwerk gilt:

- Wertpapierregistereinträge können während der Aufbewahrungsfrist (eWpG §15, TVTG Art. 10) nicht gelöscht werden — die Ausnahme für gesetzliche Verpflichtungen greift
- KYC-Dokumente müssen für die Dauer der Geschäftsbeziehung zuzüglich der Aufbewahrungsfrist aufbewahrt werden
- Der aktuelle Löschdienst versieht ausgewählte `AppUser`-Kontakt-/Authentifizierungsfelder nach Prüfung durch den Betreiber mit einem Tombstone; er löscht nicht alle mit einer Entität verknüpften personenbezogenen Daten

Aktuelles Verhalten:

1. Eine Löschanforderung erzeugt ein Arbeitselement für den Betreiber.
2. Der Abschluss ersetzt ausgewählte `AppUser`-Namens-/E-Mail-Werte, löscht den Passwort-Hash und deaktiviert den Nutzer.
3. Die Abdeckung für `NaturalPerson`, KYC-Dokumente, Bestände, Transaktionen und andere verknüpfte Daten ist unvollständig; kein DEK wird zerstört, da eine DEK-Verschlüsselung pro Datensatz nicht implementiert ist.
4. Anfrage-/Abschlussereignisse werden ausgegeben, dies allein beweist jedoch keine vollständige Löschung oder rechtskonforme Bearbeitung der Anfrage.
5. Solange die Entität noch aktive Registerbestände hat, muss der Betreiber beim Abschluss den **beibehaltenen Benachrichtigungsweg** für die gesetzlichen §19-eWpG-Auszüge benennen; er wird an der Anfrage und im Audit-Ereignis gespeichert. Ein §19-Auszug kann nicht mehr an einen gelöschten oder deaktivierten Nutzer gemailt werden: Er wird als `FAILED` (`NO_LAWFUL_CHANNEL`) erfasst, löst einen Audit-Alarm aus und wird nicht wiederholt. Die Regelung für solche Inhaber (Aufbewahrungsweg) ist eine offene Entscheidung.

---

## Endpunkte für Betroffenenrechte { #data-subject-rights-endpoints }

| Recht | Endpunkt |
|---|---|
| Art. 15/20 — Auskunft/Übertragbarkeit | `GET /api/v1/me/dsar/export` — teilweiser Export von Rechtsträger-/KYC-Status; kein vollständiger Export personenbezogener Daten |
| Art. 16 — Berichtigung | Hier ist kein vollständiger DSAR-Berichtigungs-Workflow dokumentiert |
| Art. 17 — Löschung | `POST /api/v1/me/dsar/erasure` — zeichnet eine Anfrage zur Prüfung durch den Betreiber auf; abgeschlossene Anfragen versehen derzeit nur ausgewählte `AppUser`-Felder mit einem Tombstone |

Die Anfrage- und Abschluss-Workflows geben Audit-Ereignisse aus. Die durchgängige DSAR-Abdeckung und die Vollständigkeit des Audits müssen noch überprüft werden.

---

## Art. 32 — Sicherheit der Verarbeitung { #art-32-security-of-processing }

Umgesetzte technische Maßnahmen:

| Maßnahme | Umsetzung |
|---|---|
| Verschlüsselung während der Übertragung | TLS 1.3 auf allen Endpunkten (Kong + Backend) |
| Verschlüsselung im Ruhezustand | `NaturalPerson`-Feldverschlüsselung ist nicht implementiert; Datenbank-/Objektspeicherverschlüsselung auf Bereitstellungsebene muss separat konfiguriert und überprüft werden |
| Zugriffskontrolle | Rollenbasiert (`@PreAuthorize`) + Step-up für sensible Lesevorgänge |
| Audit-Protokollierung | Manipulationssicher nachweisbare Hash-Kette für alle Vorgänge |
| MFA | WebAuthn / TOTP für alle Betreiberkonten |
| Pseudonymisierung | `NaturalPerson.id` (UUID) wird in modulübergreifenden Referenzen anstelle des Namens verwendet |
| Reaktion auf Vorfälle | Es gibt manuelle Vorfallsaufzeichnungen und Fristenüberwachung; eine Automatisierung der Benachrichtigung von Behörden/betroffenen Personen ist nicht implementiert |

---

## Art. 33/34 — Benachrichtigung über einen Verstoß { #art-3334-breach-notification }

Kommt es zu einer Verletzung des Schutzes personenbezogener Daten:

- Art. 33: Benachrichtigen Sie die **Aufsichtsbehörde** innerhalb von 72 Stunden nach Kenntniserlangung
- Art. 34: Benachrichtigen Sie **betroffene Personen** unverzüglich, wenn der Verstoß ein hohes Risiko darstellt

Es ist kein automatisierter Workflow zur DSGVO-Meldung von Verstößen an Behörden oder betroffene Personen implementiert. Betreiber müssen einen einsatzspezifischen Prozess einrichten, testen und nachweisen.

## Entity-Lebenszyklus und DSAR-Umfang {#entity-lifecycle}

- **Zustandsautomat:** PENDING_ONBOARDING -> ACTIVE nur durch das Onboarding; ACTIVE <-> SUSPENDED (Sperren und Reaktivieren erfordern Step-up, einen zweiten Freigeber und eine Begründung); CLOSED (Kündigung) und DISSOLVED (Verschmelzung) sind endgültig. Die Reaktivierung wird abgelehnt, solange KYC abgelaufen/abgelehnt ist, ein Screening-Treffer offen ist oder ein Sperrvermerk aktiv ist.
- **Kündigung** wird abgelehnt, solange Verpflichtungen offen sind (begebene Wertpapiere, nicht abgewickelte Trades, Repo-/Lending-Positionen, offene Corporate Actions und Registerübertragungen, Sperrvermerk-Bestände), außer jede wird mit Begründung bestätigt. Bestätigte Verpflichtungen werden zu Folgeaufgaben (`/api/v1/entity-tasks`, Gauge `registerwerk_offboarding_open_tasks`), die offen bleiben, bis ein Operator sie erledigt. Nutzer werden deaktiviert, Sitzungen und ungenutzte Aktionstoken widerrufen.
- **On-Chain:** Die Sperre suspendiert die Org auf jeder Chain; Kündigung und Verschmelzung widerrufen zusätzlich die KYC/AML-Claims. Eine Reaktivierung stellt on-chain nichts automatisch wieder her, sondern erzeugt die Aufgabe `CHAIN_REINSTATEMENT_REQUIRED` für die Wiedereinsetzung im Vier-Augen-Prinzip.
- **Datenänderungen:** Änderung von Name, LEI oder Land löst ein erneutes Screening aus und für APPROVED-Entitäten eine Aufgabe `KYC_REVIEW_REQUIRED` (der KYC-Status ändert sich nicht automatisch). Herabstufung der Kundenkategorie und Verschmelzungen erfordern einen zweiten Freigeber und eine Begründung; das Audit-Ereignis enthält Vorher und Nachher.
- **DSAR:** Der Export umfasst nur Entity-Stammdaten und nennt, was nicht enthalten ist. Eine Löschung endet als `COMPLETED_PARTIAL` mit Listen zu gelöscht, aufbewahrt (Rechtsgrundlage) und nicht abgedeckt. Die Durchsetzung von Aufbewahrungsfristen erfolgt manuell; nichts wird automatisch gelöscht (Fristen sind eine offene Rechtsentscheidung).
