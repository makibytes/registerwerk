---
title: Rollen und Berechtigungen
description: Wer Registerwerk nutzt, was diese Personen dürfen und welche aufsichtsrechtliche Pflicht jede Rolle adressiert.
---

# Rollen und Berechtigungen

Registerwerk ist mandantenfähig: Eine Betreiberinstallation bedient viele Kunden-Rechtsträger. Der Zugriff wird über einen Rollensatz gesteuert, der im Enum `AppUserRole` definiert und über `@PreAuthorize` an jeder Controller-Methode durchgesetzt wird.

---

## Rollenüberblick

| Rolle | Portal | Wer sie hält | Aufsichtsrechtliche Pflicht |
|---|---|---|---|
| `REGISTRY_ADMIN` | Betreiber | Registermitarbeitende | §15 eWpG registerführende Stelle; §10 GwG Geldwäschebeauftragter |
| `COMPLIANCE_OFFICER` | Betreiber | Compliance-/AML-Team | §7 GwG Compliance-Beauftragter; Art. 8 AMLD6 |
| `AUDIT` | Betreiber | Interne/externe Prüfer | §15(3) eWpG Zugang zu Aufzeichnungen |
| `SUPPORT_AGENT` | Betreiber | Support-Mitarbeitende | Nur schreibgeschützte Kundensitzungen; keine regulatorische Funktion |
| `ISSUER` | Kunde | Wertpapieremittenten | §4 eWpG Emittentenpflichten |
| `INVESTOR` | Kunde | Token-Inhaber / Anleger | |
| `COMPANY_ADMIN` | Kunde | Administratoren des Emittenten | |
| `TRADER` | Kunde | Ausführungszugang für Handelsplatz-Anbindungen | Art. 26 MiFIR Meldewesen |

---

## Betreiberrollen

### REGISTRY_ADMIN

Die Rolle mit den weitreichendsten Rechten. Ein `REGISTRY_ADMIN` kann:

- [Rechtsträger](../intro/concepts.md#kundenentitaten) anlegen, ändern und deaktivieren
- [KYC-Dokumente](../compliance/kyc-aml.md) genehmigen und ablehnen
- [Wertpapier-Token](../token-standards/index.md) ausbringen und verwalten
- [Sperrvermerke](../compliance/sperrvermerk.md) (Handelsbeschränkungen) eintragen — erfordert [Step-up-Authentifizierung](../compliance/step-up-mfa.md)
- Token zwangsübertragen und zwangsvernichten — erfordert Step-up + Vier-Augen-Prinzip
- Schreibgeschützte Identitätsübernahme-Sitzungen für Supportzwecke starten (Step-up und dokumentierte Begründung; Schreibsitzungen gibt es nur im Demo-Modus), siehe [Identitätsübernahme](#identitatsubernahme)
- Auf sämtliche [Audit-Log](../platform/audit-log.md)-Einträge zugreifen
- [MiFIR](../compliance/mifir.md)- und [DAC8](../compliance/dac8.md)-Meldeexporte auslösen

!!! warning "Zwangsoperationen erfordern doppelte Kontrolle"
    Zwangsübertragung, Zwangsvernichtung und Zwangsfreigabe sind unumkehrbare On-Chain-Vorgänge. Die aktuelle Implementierung verlangt, dass ein zweiter, anderer Betreiber (ein `REGISTRY_ADMIN` oder `COMPLIANCE_OFFICER`) die Vier-Augen-Genehmigung erteilt; eine eigene Anwendungsrolle `SECOND_APPROVER` gibt es nicht. Ob das rechtlich und richtlinienseitig genügt, erfordert eine externe Prüfung.

### COMPLIANCE_OFFICER

Auf AML/KYC-Funktionen ausgerichtet:

- Läufe und Treffer der [Sanktionsprüfung](../compliance/sanctions-screening.md) sichten und verwalten
- Prüftreffer annehmen oder ablehnen (immer mit Step-up und einem zweiten Genehmiger)
- KYC-Dokumente für die zugewiesenen Jurisdiktionen genehmigen
- [Sperrvermerke](../compliance/sperrvermerk.md) einsehen (Eintragen und Aufheben ist nur `REGISTRY_ADMIN` vorbehalten, mit Step-up und einem zweiten Genehmiger)
- Auf [DORA](../compliance/dora.md)-Vorfallsaufzeichnungen zugreifen
- Eine erneute Sanktionsprüfung auf Anforderung auslösen

### AUDIT

Lesender Zugriff auf den gesamten Prüfpfad:

- Alle [Audit-Log](../platform/audit-log.md)-Einträge lesen
- Die Unversehrtheit der Audit-Hash-Kette prüfen
- Prüfaufzeichnungen für externe Durchsicht exportieren
- Auf die Historie der Prüfläufe und die Versionen von KYC-Dokumenten zugreifen

### Genehmiger im Vier-Augen-Prinzip

Die Genehmigung im Vier-Augen-Prinzip ist derzeit eine Fähigkeit eines zweiten, anderen Nutzers mit der Rolle `REGISTRY_ADMIN` oder `COMPLIANCE_OFFICER`, keine eigene Anwendungsrolle. Der Genehmigende muss vom Auslösenden verschieden sein, in der Datenbank noch aktiv sein und die konfigurierten Step-up-Prüfungen bestehen. Anträge lassen sich in der Freigabe-Warteschlange der Anwendung stellen und genehmigen (siehe [Step-up-MFA und Vier-Augen-Prinzip](../compliance/step-up-mfa.md)).

### SUPPORT_AGENT

Betreiberpersonal für den Kundensupport. Ein `SUPPORT_AGENT` kann Kunden-Rechtsträger auflisten und **schreibgeschützte** [Identitätsübernahme](#identitatsubernahme)-Sitzungen starten (Step-up und Begründung erforderlich). Er kann nichts ändern und hat keine regulatorische Funktion. Vergabe und Entzug der Rolle erfordern Step-up und einen zweiten Genehmiger.

---

## Kundenrollen

Kundennutzer greifen über das Kunden-Frontend (`:44201`) auf die Plattform zu; dessen API-Aufrufe laufen über Kong. Ihr JWT trägt einen Claim `entityId` (ebenfalls als `entity_id` ausgegeben), der angibt, zu welcher `LegalEntity` sie gehören; daraus erzwingt das Backend bei jeder Anfrage die Datentrennung.

`X-Entity-Id` ist ein *Header*-Name, kein Claim — und einer, den Kong bei eingehenden Anfragen bewusst **entfernt**, damit er nicht gefälscht werden kann. Nichts im Backend vertraut ihm.

### ISSUER

Ein Emittent kann:

- Eigene [Asset](../token-standards/index.md)-Definitionen anlegen und verwalten
- Die Token-Ausbringung anstoßen (sofern erforderlich vorbehaltlich der Genehmigung durch den Betreiber)
- Die Aufnahme von Anlegern für die eigenen Token verwalten
- [Kapitalmaßnahmen](../intro/concepts.md) — Dividenden, Splits, vorzeitige Kündigungen — zur Prüfung durch den Betreiber vorschlagen und einen noch ungeprüften Vorschlag zurückziehen
- Bestätigen, dass die Abwicklung einer Kapitalmaßnahme bereit ist — die erste der beiden erforderlichen Parteien, neben der Bestätigung eines Betreibers
- Die Historie der Kapitalmaßnahmen für die eigenen Wertpapiere einsehen
- Depotauszüge und aufsichtsrechtliche Unterlagen herunterladen

### INVESTOR

Ein Anleger kann:

- Sein Portfolio einsehen (gehaltene Token, Bestände)
- Übertragungsanfragen annehmen
- Die Transaktionshistorie einsehen
- Kapitalmaßnahmen einsehen, die die eigenen Bestände betreffen, und Abwicklungsbestätigungen herunterladen
- Die eigenen Depotauszüge herunterladen

### COMPANY_ADMIN

Verwaltet Nutzer und Rollen innerhalb eines Kunden-Rechtsträgers:

- Unternehmensnutzer einladen und entfernen
- Die Rollen `ISSUER` / `INVESTOR` / `TRADER` innerhalb des eigenen Rechtsträgers vergeben
- Den KYC-Status des Rechtsträgers einsehen (aber nicht genehmigen — das können nur Betreiber)

### TRADER

Ein maschineller oder menschlicher Nutzer, der zur Interaktion mit Handelsplatz-Anbindungen berechtigt ist:

- Verkaufsangebote einstellen und verwalten
- Berichte zu Handelsausführungen einsehen
- Die Plattform führt ein Order- und Ausführungsprotokoll (Export für den Betreiber unter `/api/v1/admin/trading/order-history`); sie erstattet **keine** MiFIR-RTS-22-Meldungen — siehe [MiFIR](../compliance/mifir.md) (Entwurf, nicht validiert)

---

## Identitätsübernahme

Mit der Identitätsübernahme öffnet Betreiberpersonal das Kundenportal innerhalb der Organisation eines Kunden, um Probleme zu untersuchen. Sie ist abgesichert und standardmäßig schreibgeschützt:

- Der Start erfordert [Step-up-Authentifizierung](../compliance/step-up-mfa.md) und eine verpflichtende schriftliche Begründung (mindestens 15 Zeichen, dazu optional eine Ticket-Referenz)
- Der Standardmodus ist **schreibgeschützt**; der Schreibmodus (`ACT_ON_BEHALF`) braucht einen zweiten Genehmiger und steht **nur im Demo-Modus** zur Verfügung. Im Produktionsmodus ist jede Sitzung schreibgeschützt
- `REGISTRY_ADMIN` und `SUPPORT_AGENT` können schreibgeschützte Sitzungen starten; eine Schreibsitzung nur `REGISTRY_ADMIN`. `SUPPORT_AGENT` darf sonst nichts
- Der Startaufruf liefert kein Token: Ein Einmalcode (60 Sekunden) wird gegen ein httpOnly-Sitzungscookie getauscht. Die Sitzung dauert höchstens 30 Minuten
- Das `sub` des Tokens bleibt die Nutzer-ID des **Betreibers**, sodass jede Handlung dem Betreiber und nie dem Kunden zugerechnet wird; `imp` kennzeichnet sie im [Audit-Log](../platform/audit-log.md)
- Sitzungen werden erfasst und sind für die Unternehmensadministratoren des Kunden sichtbar
- Sie ist für alle `REGISTRY_ADMIN`-Nutzer über die Übernahmeleiste im Kunden-Frontend sichtbar

Bei `ENTRA_ENABLED=true` ist die Identitätsübernahme vollständig nicht verfügbar — das Backend weigert sich, eine Sitzung im Namen eines Kunden auszustellen. [Identitätsübernahme](../operator/customers/impersonation.md) behandelt die Einzelheiten und die Steuerung.
