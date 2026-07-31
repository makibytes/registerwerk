---
title: Rollen und Berechtigungen
description: Wer Registerwerk nutzt, was diese Personen dürfen und welche aufsichtsrechtliche Pflicht jede Rolle adressiert.
---

# Rollen und Berechtigungen

Registerwerk ist mandantenfähig: Eine Betreiberinstallation bedient viele Kunden-Rechtsträger. Der Zugriff wird über einen Rollensatz gesteuert, der im Enum `AppRole` definiert und über `@PreAuthorize` an jeder Controller-Methode durchgesetzt wird.

---

## Rollenüberblick

| Rolle | Portal | Wer sie hält | Aufsichtsrechtliche Pflicht |
|---|---|---|---|
| `REGISTRY_ADMIN` | Betreiber | Registermitarbeitende | §15 eWpG registerführende Stelle; §10 GwG Geldwäschebeauftragter |
| `COMPLIANCE_OFFICER` | Betreiber | Compliance-/AML-Team | §7 GwG Compliance-Beauftragter; Art. 8 AMLD6 |
| `AUDITOR` | Betreiber | Interne/externe Prüfer | §15(3) eWpG Zugang zu Aufzeichnungen |
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
- Kundennutzer zu Supportzwecken übernehmen — eine ständige Fähigkeit, siehe den Vorbehalt unten
- Auf sämtliche [Audit-Log](../platform/audit-log.md)-Einträge zugreifen
- [MiFIR](../compliance/mifir.md)- und [DAC8](../compliance/dac8.md)-Meldeexporte auslösen

!!! warning "Zwangsoperationen erfordern doppelte Kontrolle"
    Zwangsübertragung, Zwangsvernichtung und Zwangsgenehmigung sind on-chain unumkehrbare Operationen. Die derzeitige Umsetzung verlangt, dass ein zweiter, anderer `REGISTRY_ADMIN` das Dual-Control-Token beisteuert; eine Anwendungsrolle `SECOND_APPROVER` gibt es nicht. Ihre rechtliche und regulatorische Angemessenheit bedarf externer Prüfung.

### COMPLIANCE_OFFICER

Auf AML/KYC-Funktionen ausgerichtet:

- Läufe und Treffer der [Sanktionsprüfung](../compliance/sanctions-screening.md) sichten und verwalten
- Prüftreffer annehmen oder ablehnen (bei Rechtsträgern mit hohem Risiko im Vier-Augen-Prinzip)
- KYC-Dokumente für die zugewiesenen Jurisdiktionen genehmigen
- [Sperrvermerke](../compliance/sperrvermerk.md) eintragen und aufheben — erfordert Step-up
- Auf [DORA](../compliance/dora.md)-Vorfallsaufzeichnungen zugreifen
- Eine erneute Sanktionsprüfung auf Anforderung auslösen

### AUDITOR

Lesender Zugriff auf den gesamten Prüfpfad:

- Alle [Audit-Log](../platform/audit-log.md)-Einträge lesen
- Die Unversehrtheit der Audit-Hash-Kette prüfen
- Prüfaufzeichnungen für externe Durchsicht exportieren
- Auf die Historie der Prüfläufe und die Versionen von KYC-Dokumenten zugreifen

### Genehmiger im Vier-Augen-Prinzip

Die Genehmigung im Vier-Augen-Prinzip ist derzeit eine Fähigkeit eines zweiten, anderen `REGISTRY_ADMIN`, keine eigene Anwendungsrolle. Der Genehmigende muss vom Auslösenden verschieden sein und die konfigurierten Step-up-Prüfungen bestehen.

---

## Kundenrollen

Kundennutzer greifen über das Kunden-Frontend (`:4201`) auf die Plattform zu; dessen API-Aufrufe laufen über Kong. Ihr JWT trägt einen Claim `entityId` (ebenfalls als `entity_id` ausgegeben), der angibt, zu welcher `LegalEntity` sie gehören; daraus erzwingt das Backend bei jeder Anfrage die Datentrennung.

`X-Entity-Id` ist ein *Header*-Name, kein Claim — und einer, den Kong bei eingehenden Anfragen bewusst **entfernt**, damit er nicht gefälscht werden kann. Nichts im Backend vertraut ihm.

### ISSUER

Ein Emittent kann:

- Eigene [Asset](../token-standards/index.md)-Definitionen anlegen und verwalten
- Die Token-Ausbringung anstoßen (sofern erforderlich vorbehaltlich der Genehmigung durch den Betreiber)
- Die Aufnahme von Anlegern für die eigenen Token verwalten
- Die Historie der [Kapitalmaßnahmen](../intro/concepts.md) für die eigenen Wertpapiere einsehen
- Depotauszüge und aufsichtsrechtliche Unterlagen herunterladen

### INVESTOR

Ein Anleger kann:

- Sein Portfolio einsehen (gehaltene Token, Bestände)
- Übertragungsanfragen annehmen
- Die Transaktionshistorie einsehen
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
- Diese Handlungen werden über [MiFIR RTS 22](../compliance/mifir.md) an die Aufsicht gemeldet

---

## Identitätsübernahme

Nutzer mit `REGISTRY_ADMIN` können einen Kundennutzer übernehmen, um Probleme zu untersuchen oder beim Onboarding zu helfen. Die Übernahme:

- Stellt ein kurzlebiges Token aus, dessen `sub` die Nutzer-ID des **Betreibers** bleibt, sodass jede Handlung dem Betreiber und nie dem Kunden zugerechnet wird
- Wird im [Audit-Log](../platform/audit-log.md) festgehalten, gekennzeichnet mit `imp`, sodass übernommene Handlungen unterscheidbar sind
- Ist für alle `REGISTRY_ADMIN`-Nutzer über die Übernahmeleiste im Kunden-Frontend sichtbar
- Endet mit dem Token; steigen Sie neu ein, statt zu verlängern

!!! warning "Die Identitätsübernahme ist nicht step-up-geschützt"
    Der `AdminImpersonationController` trägt kein `@RequiresStepUp`. Jeder `REGISTRY_ADMIN` kann das Portal jedes Kunden ohne zweite Authentifizierungsabfrage und ohne eine zweite Person betreten.

    Behandeln Sie das als Frage der Kontrolle, nicht der Technik: Halten Sie den Adminkreis klein, verlangen Sie einen außerhalb der Plattform dokumentierten Grund, und sehen Sie Übernahmeereignisse regelmäßig durch. [Identitätsübernahme](../operator/customers/impersonation.md) behandelt ihre Steuerung.

Bei `ENTRA_ENABLED=true` ist die Identitätsübernahme zudem vollständig nicht verfügbar — das Backend weigert sich, eine Sitzung im Namen eines Kunden auszustellen.
