---
title: Dashboard
---

# Dashboard

Das Dashboard ist der erste Bildschirm nach der Anmeldung. Es gibt einen Echtzeitüberblick über Ihre Aktivität im Register, zugeschnitten auf Ihre Rolle.

## Übersichtskarten

Oben auf dem Dashboard finden Sie Übersichtskarten. Welche angezeigt werden, hängt von Ihrer Rolle ab:

### Dashboard für Emittenten

| Karte | Beschreibung |
|------|-------------|
| **Active Issuances** | Anzahl der Token derzeit im Zustand ISSUED |
| **Pending Approval** | Emissionen, die auf die Prüfung durch den Betreiber warten |
| **Total Investors** | Eindeutige Anleger-Wallets über alle Ihre Token hinweg |
| **Networks** | Verschiedene Blockchain-Netze, in denen Sie Token ausgebracht haben |

### Dashboard für Anleger

| Karte | Beschreibung |
|------|-------------|
| **Token Holdings** | Anzahl verschiedener Wertpapier-Token, die Sie halten |
| **Connected Wallets** | Mit Ihrem Konto registrierte Wallets |
| **Recent Transfers** | Übertragungen der letzten 30 Tage |

### Dashboard für Prüfer

| Karte | Beschreibung |
|------|-------------|
| **Total Issuances** | Alle Emissionen im Register |
| **Transfers (30d)** | Alle On-Chain-Übertragungsereignisse der letzten 30 Tage |
| **Active Issuers** | Anzahl der Emittenten mit mindestens einem aktiven Token |
| **Pending KYC Reviews** | KYC-Einreichungen, die auf die Prüfung durch den Betreiber warten (nur lesend) |

## Aktivitätsverlauf

Unter den Übersichtskarten zeigt das Feld **Recent Activity** die jüngsten für Ihr Konto relevanten Ereignisse. Jeder Eintrag enthält:

- **Zeitstempel** — wann das Ereignis eintrat (Ihre lokale Zeitzone)
- **Ereignistyp** — z. B. *Issuance Created*, *Transfer*, *KYC Approved*
- **Gegenstand** — der betroffene Token oder Rechtsträger
- **Netz** — das Blockchain-Netz (mit Chain-Symbol)

Klicken Sie eine Zeile an, um direkt zur zugehörigen Detailseite zu gelangen.

## Schnellaktionen

Das Feld **Quick Actions** bietet Ein-Klick-Navigation zu den häufigsten Aufgaben Ihrer Rolle:

- **Emittent**: New Issuance, Manage Investors, View Pending Approvals
- **Anleger**: View Holdings, Connect Wallet, Download Statement
- **Prüfer**: Open Audit Log, Search Transfers, Export Report

## Netzstatus

Am unteren Rand des Dashboards zeigt ein laufend aktualisiertes Raster **Network Status** an, ob jedes konfigurierte Blockchain-Netz derzeit erreichbar und synchron ist. Grün bedeutet, der Indexer ist aktuell; Gelb, dass er mehr als 10 Blöcke hinter der Chain-Spitze liegt; Rot, dass er nicht verfügbar ist.

!!! tip
    Zeigt ein Netz Rot, können die On-Chain-Daten für dieses Netz veraltet sein. Warten Sie einige Minuten und aktualisieren Sie. Hält das Problem an, wenden Sie sich an den Registerbetreiber.


## Daten aktualisieren

Die Dashboard-Daten aktualisieren sich automatisch alle 30 Sekunden. Eine sofortige Aktualisierung erzwingen Sie mit der Schaltfläche **Refresh** oben rechts in jedem Feld.
